# Tag Reaper Sentinel Next

Clean test build for the Chainway C316H four-port UHF RFID reader.

## Included in v0.3.0

- Native Android Java app
- Chainway `DeviceAPI_ver20251103_release.aar`
- C316H connection and inventory callback
- Independent enable/disable controls for ANT 1–4
- Independent 5–30 dBm power controls for ANT 1–4
- Hardware power read-back before scanning
- New session, start, pause, resume, end, and clear controls
- Session recovery after app closure or force-stop
- Elapsed, active, and paused timers
- EPC grouping with first seen, last seen, total reads, strongest RSSI
- Separate counts and RSSI evidence for each antenna
- GitHub Actions debug APK build

## First hardware test

1. Upload this repository to GitHub.
2. Open **Actions → Build Tag Reaper Sentinel APK → Run workflow**.
3. Download `tag-reaper-sentinel-debug-apk`.
4. Install the APK on the Android device connected to the C316H.
5. Connect the reader and create a new session.
6. Enable one antenna at a time and test power at 30, 20, 10, and 5 dBm.
7. Confirm the app refuses to scan if the hardware does not accept/read back the requested power.
8. Force-stop the app during a session, reopen it, and confirm recovery.

## Deliberately not included yet

- Completed-session history
- CSV/JSON export
- GPS and SageWire enrichment
- Offline synchronization queue
- HerdMate synchronization

Those come after the antenna-power checkpoint passes on the real reader.
