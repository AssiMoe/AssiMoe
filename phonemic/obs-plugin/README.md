# PhoneMic OBS Plugin (Windows x64)

Source code: `src/phone-mic.cpp`. Compatible with the custom PhoneMic Android app on the `phonemic-apk-build` branch.

- Audio: 48,000 Hz, 16-bit signed little-endian PCM, mono, 480 frames per packet (10 ms).
- Datagram header: AMIC, version 1, little-endian payload length 960, sequence, 8-digit pairing code, session ID.
- Port: 49888/UDP by default.
- Return acknowledgment: AMOK + pairing code + session ID.
- OBS source ID: `phone_mic_udp`.
- The pairing code is not encryption. Use on a trusted local network only.

## Installing

Unpack the artifact ZIP. Double-click `Installieren.cmd` and confirm UAC if asked. Then restart OBS and add `PhoneMic – Handy als Mikrofon` under Sources. On Windows 33+ the installer writes the DLL to `C:\ProgramData\obs-studio\plugins\phone-mic-obs\phone-mic-obs.dll` (new plugin format); with older OBS versions it uses the legacy `bin\64bit` subfolder. If OBS does not receive audio, allow incoming UDP/49888 in Windows Defender Firewall for your private network.

The plugin is GPL-2.0-or-later. No release candidate should be considered verified without an OBS runtime test.
