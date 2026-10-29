# VReal wire protocol v1

All messages are UDP datagrams, little-endian. One socket per side:

- PC listens on **9944**
- Phone listens on **9945**

## Header (4 bytes, every message)

| Offset | Type | Field |
|---|---|---|
| 0 | 2 bytes | magic `"VR"` (0x56 0x52) |
| 2 | u8 | version = 1 |
| 3 | u8 | type |

## Messages

| Type | Name | Direction | Body |
|---|---|---|---|
| 1 | HELLO | phone → PC | u16 width, u16 height (landscape panel px), f32 refresh Hz, u8 codec mask (bit0 H.264, bit1 HEVC, bit2 AV1) |
| 2 | CONFIG | PC → phone | u8 codec (0 H.264, 1 HEVC, 2 AV1), u16 width, u16 height, u16 fps |
| 3 | POSE | phone → PC | u32 seq, u64 timestamp µs (phone clock), f32 qx, qy, qz, qw |
| 4 | VIDEO | PC → phone | u32 frame index, u16 fragment index, u16 fragment count, u64 pose timestamp µs, u8 flags (bit0 keyframe), then payload (≤ 1400 bytes) |
| 5 | IDR_REQUEST | phone → PC | (empty) |

## Flow

1. The phone sends HELLO once per second (also serves as a keepalive) to the configured PC host,
   or to the broadcast address of every network interface. Using each interface's broadcast address
   is what makes discovery work over USB tethering, where the default route is not the USB link.
2. The PC answers each HELLO with CONFIG and streams VIDEO to the HELLO's source address.
   The phone learns the PC address from the CONFIG source and sends POSE there for every sensor event.
3. VIDEO payloads are Annex-B access units. Keyframes carry parameter sets (VPS/SPS/PPS) in-band.
4. If a frame is lost, the phone drops frames until the next keyframe and sends IDR_REQUEST
   (rate-limited).

## Pose

- Quaternion of the head in OpenVR axes: +X right, +Y up, +Z backward (looking along −Z).
- Yaw is relative to the last recenter.
- The timestamp is `SystemClock.elapsedRealtimeNanos() / 1000` on the phone. The PC echoes the pose
  timestamp it rendered with in VIDEO, so the phone can measure motion-to-photon latency without clock sync.
