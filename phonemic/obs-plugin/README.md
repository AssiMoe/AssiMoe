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

## USB-Kabel statt WLAN (PhoneMic 1.2)

PhoneMic nutzt **USB-Tethering**, also ein lokales USB-Netzwerk. Es ist keine USB-Audio-Geraeteemulation, und ADB/USB-Debugging ist nicht erforderlich.

1. Handy per datenfaehigem USB-Kabel mit dem Windows-PC verbinden.
2. Android: Einstellungen -> Hotspot & Tethering -> USB-Tethering aktivieren.
3. Das PhoneMic-OBS-Plugin wie gewohnt installieren und die Quelle in OBS aktivieren.
4. PhoneMic Android: Verbindung "USB-Kabel (Tethering)" waehlen.
5. "OBS-PC ueber USB automatisch suchen" antippen. Wenn die Suche keinen PC findet, die IPv4 des Windows-USB-Netzwerkadapters manuell eintragen (Windows Einstellungen -> Netzwerk & Internet -> Ethernet).
6. UDP-Port und Verbindungscode beibehalten, dann "Mikrofon starten".

Die Suche sendet nur auf Android-USB-Netzwerkschnittstellen Discovery-Pakete und beruecksichtigt den Verbindungscode. Das Windows-Plugin antwortet darauf. Es bleibt bei UDP 49888 fuer Audio.

Windows-Firewall: Der Installer legt die Regel "PhoneMic OBS (USB/WLAN lokal)" an. Sie erlaubt eingehende UDP-Pakete auf Port 49888 **nur aus dem lokalen Subnetz**, auch wenn Windows den USB-Adapter als Oeffentlich erkennt.

USB-Tethering muss aus Android-Gruenden in den Systemeinstellungen aktiviert werden. Es ist nicht garantiert, dass jeder Android-Hersteller USB-Tethering oder die automatische PC-Suche unterstuetzt. Die manuelle IP-Eingabe bleibt verfuegbar.
