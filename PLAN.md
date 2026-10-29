# VReal: phone as a PC VR headset (sim racing)

Goal: use an Android phone in a headset viewer as the display for SteamVR racing sims on a
Windows PC (RTX 5060 Ti), with low latency over a USB cable and Wi-Fi as a fallback.
The phone only displays the image and tracks head rotation. The game reads the Logitech wheel directly.

## Architecture

```
Windows PC                                     Android phone
┌─────────────────────────────┐                ┌──────────────────────────────┐
│ Racing sim (SteamVR/OpenXR) │                │ Pose: GAME_ROTATION_VECTOR   │─┐
│          ↓                  │◄─ pose (UDP) ──┼──────────────────────────────┼─┘
│ SteamVR + VReal driver      │                │                              │
│  - seated HMD, phone pose   │                │ MediaCodec (low latency)     │
│  - IVRVirtualDisplay frame  │                │          ↓                   │
│          ↓                  │                │ SurfaceTexture → GL          │
│ NVENC (D3D11 texture input) │── video (UDP)─►│ reprojection + lens distort  │
│          ↓                  │                │          ↓                   │
│ packetizer                  │                │ display                      │
└─────────────────────────────┘                └──────────────────────────────┘
 Transport: USB tethering (primary) | Wi-Fi (fallback); same UDP protocol (docs/protocol.md)
```

## Decisions

| Area | Choice |
|---|---|
| PC runtime | SteamVR OpenVR driver DLL (C++), seated HMD |
| Frame capture | `IVRVirtualDisplay` first (SteamVR composites the layers); direct mode only if needed |
| Encoder | NVENC via the Video Codec SDK, zero-copy from D3D11; HEVC first, AV1 if the phone decodes it |
| Phone | Kotlin, no external dependencies; NDK only if profiling demands it |
| Tracking | 3DoF + neck model; ARCore 6DoF optional later |
| Cable | USB tethering (built-in Windows driver, UDP). Raw USB (AOA + WinUSB) only if tethering isn't good enough |
| Wheel | Not our concern: the game reads it through G HUB. Recenter = keyboard hotkey bound to a wheel button |

## Targets

- Stream resolution = phone panel (e.g. 2400×1080 → ~1200×1080 per eye), with the game supersampled on the PC
- 72–90 Hz locked, if the phone panel supports it
- Under 40 ms from head motion to screen on cable; bitrate 100–200 Mbps on cable, 30–80 Mbps on Wi-Fi

## Phases

- [ ] **0. Baseline**: Try ALVR + PhoneVR (and the iVRy/VRidge trials) over USB tethering with a sim. Record the latency/quality to beat and how good the phone decoder is.
- [x] **1. Phone app + test streamer**
  - [x] UDP protocol, discovery via HELLO broadcast on every interface (works over USB tethering)
  - [x] Pose from `GAME_ROTATION_VECTOR`, converted to OpenVR axes, tap to recenter
  - [x] Frame reassembly, loss detection, IDR request
  - [x] MediaCodec low-latency decode → SurfaceTexture → side-by-side lens distortion
  - [x] Stats overlay (fps, pose→display latency, loss, Mbps)
  - [x] `tools/test_streamer.py` (ffmpeg test pattern, libx26x or NVENC)
  - [x] Verified on a real phone (2772×1280 @ 120 Hz, Qualcomm `c2.qti.hevc.decoder`, H.264/HEVC/AV1 decode): 120 fps decode, 200 Hz pose
  - [x] Phone prefers the USB link over Wi-Fi when both are up
  - [ ] Tune lens parameters for the actual viewer
- [ ] **2. SteamVR driver** (`driver/`, Windows, Visual Studio): HMD device, pose from phone, virtual display → NVENC → UDP
- [ ] **3. Smoothness**: rotation reprojection on the phone, pose prediction + clock sync, slice encoding, frame pacing
- [ ] **4. Polish**: automatic cable/Wi-Fi selection, adaptive bitrate + FEC on Wi-Fi, foveated bitrate, per-game presets, lens profiles (Cardboard QR)

## Risks

- Phone decoder latency varies a lot (5–30 ms). Measure early (stats overlay).
- Thermal throttling on long sessions. A cable at least keeps the phone charged.
- A wrong lens profile causes eye strain, so lens parameters must be adjustable.
