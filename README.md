# VReal

Use an Android phone as a PC VR headset for sim racing. USB cable first, Wi-Fi as a fallback.
See [PLAN.md](PLAN.md) for the roadmap and [docs/protocol.md](docs/protocol.md) for the wire format.

## Layout

```
phone/   Android app (Kotlin, no dependencies): pose sender, decoder, lens-distortion renderer
tools/   test_streamer.py: PC stand-in for the SteamVR driver (ffmpeg test pattern)
driver/  SteamVR driver (Phase 2, not started)
```

## Build the phone app

Needs a full JDK (17+) and the Android SDK (path in `phone/local.properties`).

```sh
cd phone
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Try it (Phase 1)

1. Connect the phone by USB and turn on **USB tethering** (Settings → Network → Hotspot & tethering).
   For Wi-Fi, put the phone and PC on the same network instead.
2. On the PC (needs Python 3 and ffmpeg on PATH):
   ```sh
   python tools/test_streamer.py                       # CPU encoder
   python tools/test_streamer.py --encoder hevc_nvenc  # NVIDIA
   ```
   On Windows, allow Python through the firewall for private networks the first time.

   On Linux, use the GPU encoder (`--encoder hevc_vaapi`) and open the port in firewalld:
   ```sh
   sudo firewall-cmd --add-port=9944/udp                                                      # until reboot
   sudo firewall-cmd --permanent --add-port=9944/udp && sudo firewall-cmd --reload  # permanently
   ```
   `adb` is in `~/Android/Sdk/platform-tools/`.
3. Open VReal on the phone. It finds the PC by broadcasting, shows a moving test pattern per eye,
   and the PC prints your head yaw/pitch. Tap the screen to recenter.

The overlay shows decode/display fps, **latency** (pose sampled → frame on screen), bitrate, lost frames and pose rate.

If discovery fails, set the PC address manually (it's remembered):

```sh
adb shell am start -n dev.vreal.phone/.MainActivity --es host <PC IP>
```

Lens tuning: `--ef k1 0.22 --ef k2 0.24 --ef scale 1.0` (same command). Higher k = stronger barrel distortion.
Hold the phone with the **USB port on the right**.
