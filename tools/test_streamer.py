#!/usr/bin/env python3
"""VReal test streamer: stands in for the SteamVR driver while developing the phone app.

Waits for the phone's HELLO, encodes an ffmpeg test pattern at the phone's panel resolution,
streams it with the VReal protocol (docs/protocol.md), and prints the head pose it receives.

    python tools/test_streamer.py                       # libx265, works anywhere
    python tools/test_streamer.py --encoder hevc_nvenc  # NVIDIA, what the real driver will use
    python tools/test_streamer.py --encoder hevc_vaapi  # Intel/AMD GPU on Linux

Windows: allow Python through the firewall for UDP 9944 (private networks), or nothing arrives.
"""
import argparse
import math
import queue
import socket
import struct
import subprocess
import sys
import threading
import time

VERSION = 1
HELLO, CONFIG, POSE, VIDEO, IDR_REQUEST = 1, 2, 3, 4, 5
CODECS = {"h264": 0, "hevc": 1}
PC_PORT = 9944
PAYLOAD = 1400

HEADER = struct.Struct("<2sBB")
HELLO_BODY = struct.Struct("<HHfB")
CONFIG_BODY = struct.Struct("<BHHH")
POSE_BODY = struct.Struct("<IQffff")
VIDEO_HEADER = struct.Struct("<2sBBIHHQB")


def encoder_args(encoder, gop, mbps, fps):
    """Returns (args before the input, args after it)."""
    rate = ["-b:v", f"{mbps}M", "-maxrate", f"{mbps}M", "-bufsize", f"{mbps * 2 / fps:.3f}M"]
    if encoder == "libx265":
        return [], ["-pix_fmt", "yuv420p", "-c:v", "libx265", "-preset", "ultrafast", "-tune", "zerolatency",
                    "-x265-params", f"repeat-headers=1:keyint={gop}:bframes=0:log-level=error"] + rate
    if encoder == "libx264":
        return [], ["-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                    "-x264-params", f"keyint={gop}:bframes=0"] + rate
    if encoder.endswith("_nvenc"):
        return [], ["-pix_fmt", "yuv420p", "-c:v", encoder, "-preset", "p1", "-tune", "ull", "-zerolatency", "1",
                    "-delay", "0", "-bf", "0", "-g", str(gop), "-forced-idr", "1", "-rc", "cbr"] + rate
    if encoder.endswith("_vaapi"):
        # Intel/AMD on Linux. Don't add -aud 1: with hevc_vaapi in ffmpeg 9.0 it collapses the stream to one packet per GOP.
        return (["-vaapi_device", "/dev/dri/renderD128"],
                ["-vf", "format=nv12,hwupload", "-c:v", encoder, "-bf", "0", "-g", str(gop),
                 "-async_depth", "1", "-rc_mode", "CBR"] + rate)
    sys.exit(f"unsupported encoder {encoder}")


def nal_starts(data, begin=0):
    """Yield (start_code_pos, nal_header_pos) for each Annex-B start code."""
    i = data.find(b"\x00\x00\x01", begin)
    while i != -1:
        pos = i - 1 if i > 0 and data[i - 1] == 0 else i
        yield pos, i + 3
        i = data.find(b"\x00\x00\x01", i + 3)


def nal_type(data, header_pos, codec):
    b = data[header_pos]
    return (b >> 1) & 0x3F if codec == "hevc" else b & 0x1F


def is_keyframe(au, codec):
    for _, h in nal_starts(au):
        if h < len(au):
            t = nal_type(au, h, codec)
            if (codec == "hevc" and 16 <= t <= 21) or (codec == "h264" and t == 5):
                return True
    return False


def au_cuts(buf, codec):
    """Offsets where a new access unit starts (excluding 0). A new AU begins at an AUD, SEI or
    parameter set, or at a slice flagged as the first in its picture, once the current AU has a slice."""
    cuts, has_slice = [], False
    for start, h in nal_starts(buf):
        if h + 2 >= len(buf):
            break
        t = nal_type(buf, h, codec)
        if codec == "hevc":
            is_slice = t < 32
            first_slice = is_slice and buf[h + 2] & 0x80
            boundary = t in (32, 33, 34, 35, 39)
        else:
            is_slice = t in (1, 5)
            first_slice = is_slice and buf[h + 1] & 0x80
            boundary = t in (6, 7, 8, 9)
        if has_slice and (boundary or first_slice):
            cuts.append(start)
            has_slice = False
        has_slice = has_slice or is_slice
    return cuts


class Streamer:
    def __init__(self, args):
        self.args = args
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4 << 20)
        self.sock.bind(("0.0.0.0", PC_PORT))
        self.phone = None
        self.hello = None
        self.config = None
        self.pose_ts = 0
        self.pose_q = (0.0, 0.0, 0.0, 1.0)
        self.poses = 0
        self.idr_requests = 0
        self.frames_sent = 0
        self.bytes_sent = 0
        self.lock = threading.Lock()
        self.connected = threading.Event()

    # --- control / pose -------------------------------------------------------------------
    def receive_loop(self):
        while True:
            data, addr = self.sock.recvfrom(2048)
            if len(data) < HEADER.size:
                continue
            magic, version, kind = HEADER.unpack_from(data)
            if magic != b"VR" or version != VERSION:
                continue
            if kind == HELLO and len(data) >= HEADER.size + HELLO_BODY.size:
                self.on_hello(addr, HELLO_BODY.unpack_from(data, HEADER.size))
            elif kind == POSE and len(data) >= HEADER.size + POSE_BODY.size:
                _, ts, qx, qy, qz, qw = POSE_BODY.unpack_from(data, HEADER.size)
                with self.lock:
                    self.pose_ts, self.pose_q = ts, (qx, qy, qz, qw)
                    self.poses += 1
            elif kind == IDR_REQUEST:
                self.idr_requests += 1  # ffmpeg can't be asked for an IDR; we rely on a short GOP

    def on_hello(self, addr, hello):
        width, height, refresh, codec_mask = hello
        a = self.args
        if self.config is None:
            w = a.width or width
            h = a.height or height
            fps = a.fps or max(30, min(120, round(refresh)))
            self.config = (CODECS[a.codec], w - w % 2, h - h % 2, fps)
            if not codec_mask & (1 << CODECS[a.codec]):
                print(f"warning: phone reports no {a.codec} decoder (mask {codec_mask:#x})")
            print(f"phone {addr[0]}: panel {width}x{height} @ {refresh:.0f} Hz, decoders mask {codec_mask:#x}")
        if self.phone != addr:
            print(f"streaming to {addr[0]}:{addr[1]}")
        self.phone = addr
        codec, w, h, fps = self.config
        self.sock.sendto(HEADER.pack(b"VR", VERSION, CONFIG) + CONFIG_BODY.pack(codec, w, h, fps), addr)
        self.connected.set()

    # --- video ----------------------------------------------------------------------------
    def run_encoder(self):
        _, w, h, fps = self.config
        a = self.args
        pre, post = encoder_args(a.encoder, a.gop or fps, a.mbps, fps)
        cmd = (["ffmpeg", "-hide_banner", "-loglevel", "error"] + pre
               + ["-re", "-f", "lavfi", "-i", f"testsrc2=size={w}x{h}:rate={fps}"]
               + post + ["-f", a.codec, "-flush_packets", "1", "pipe:1"])
        print(" ".join(cmd))
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, bufsize=0)
        for au in self.access_units(proc):
            self.send_frame(au)

    def access_units(self, proc):
        """Split ffmpeg's Annex-B output into access units.

        The last AU can't be closed by the next one's first NAL without waiting a whole frame, so a
        short pause in the output (ffmpeg flushes one packet per write) also ends the current AU.
        """
        chunks = queue.Queue()

        def reader():
            while True:
                chunk = proc.stdout.read(1 << 20)
                chunks.put(chunk)
                if not chunk:
                    return

        threading.Thread(target=reader, daemon=True).start()
        buf = bytearray()
        while True:
            try:
                chunk = chunks.get(timeout=0.003)
            except queue.Empty:
                if buf:
                    yield bytes(buf)
                    buf.clear()
                continue
            if not chunk:
                return
            buf += chunk
            start = 0
            cuts = au_cuts(buf, self.args.codec)
            for cut in cuts:
                if cut > start:
                    yield bytes(buf[start:cut])
                start = cut
            del buf[:start]

    def send_frame(self, au):
        with self.lock:
            pose_ts = self.pose_ts
        flags = 1 if is_keyframe(au, self.args.codec) else 0
        count = (len(au) + PAYLOAD - 1) // PAYLOAD
        index = self.frames_sent & 0xFFFFFFFF
        for i in range(count):
            header = VIDEO_HEADER.pack(b"VR", VERSION, VIDEO, index, i, count, pose_ts, flags)
            self.sock.sendto(header + au[i * PAYLOAD:(i + 1) * PAYLOAD], self.phone)
        self.frames_sent += 1
        self.bytes_sent += len(au)

    # --- status ---------------------------------------------------------------------------
    def status_loop(self):
        last = (time.monotonic(), 0, 0, 0)
        while True:
            time.sleep(1)
            now = (time.monotonic(), self.poses, self.frames_sent, self.bytes_sent)
            dt = now[0] - last[0]
            with self.lock:
                qx, qy, qz, qw = self.pose_q
            # Forward vector = rotate (0, 0, -1); OpenVR axes.
            fx = -2 * (qx * qz + qw * qy)
            fy = -2 * (qy * qz - qw * qx)
            fz = -(1 - 2 * (qx * qx + qy * qy))
            yaw = math.degrees(math.atan2(-fx, -fz))
            pitch = math.degrees(math.asin(max(-1.0, min(1.0, fy))))
            print(f"pose {(now[1] - last[1]) / dt:5.0f} Hz  yaw {yaw:+7.1f}  pitch {pitch:+6.1f} | "
                  f"video {(now[2] - last[2]) / dt:5.1f} fps {(now[3] - last[3]) * 8 / dt / 1e6:6.1f} Mbps | "
                  f"IDR requests {self.idr_requests}")
            last = now


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--codec", choices=CODECS, default="hevc")
    p.add_argument("--encoder", help="ffmpeg encoder (default libx265 / libx264)")
    p.add_argument("--width", type=int, help="default: phone panel width")
    p.add_argument("--height", type=int, help="default: phone panel height")
    p.add_argument("--fps", type=int, help="default: phone refresh rate")
    p.add_argument("--mbps", type=float, default=60)
    p.add_argument("--gop", type=int, help="keyframe interval in frames (default: 1 s)")
    args = p.parse_args()
    args.encoder = args.encoder or ("libx265" if args.codec == "hevc" else "libx264")

    s = Streamer(args)
    threading.Thread(target=s.receive_loop, daemon=True).start()
    print(f"listening on UDP {PC_PORT}; start VReal on the phone (USB tethering or same Wi-Fi)")
    s.connected.wait()
    threading.Thread(target=s.status_loop, daemon=True).start()
    try:
        s.run_encoder()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
