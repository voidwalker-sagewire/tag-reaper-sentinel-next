# Sentinel Next R1 — recovered Preview repair candidate

This is a narrow APK-derived repair of installed Preview 0.7.0, not the missing
original Rev5 Java source. The repository's existing app/ tree is older Rev3;
building that tree does **not** produce this candidate. Forgekeeper is excluded.

Baseline: preserved `Tag-Reaper-Sentinel-0.7.0-preview.apk`, SHA256
`2d6058b085835f682e598b80d0e38ceeb1b652d240a53a034d79de3966600c83`.

Package `com.herdmate.tagreapersentinel.next`, version `0.7.1-next-r1`, code 9,
launcher label `Sentinel Next R1`. It installs beside Preview and DeviceAPI.
It uses isolated local storage; existing records are not automatically copied.
Use the existing saved-session JSON import for migration after startup validation.
Keep old installations and exports. Do not run multiple inventory owners together.

## Changes

- RFID and scale live display requests share one pending 250ms refresh. Evidence
  processing remains per event; EPCs, antenna routing and ambiguity logic are
  unchanged. User actions keep their immediate refresh behavior. This does not
  bound the upstream RFID callback queue or prove a native SDK crash fixed.
- Duplicate-read evidence snapshots occur every five seconds rather than every
  second. Animal creation, weight lock, care saves, transitions, release and pause
  retain their original immediate durable saves. A crash may lose the newest
  repeat counts since the last checkpoint.
- Stable weight latch rejects zero, negative, NaN and infinite weight readings,
  clearing partial agreement. Three agreeing positive readings are still needed.
  This does not detect partially loaded animals or a miscalibrated scale.
  Historical records and weights are untouched.
- Java uncaught crashes are written to a bounded local text report and Android's
  original handler still runs. On next launch a report export is offered.
  Native crashes, ANRs, power loss and hard process kills are not captured.

## Validation on 2026-10-08 UTC

- APK rebuilt successfully; v2/v3 signatures and zip alignment verified.
- Manifest confirms distinct package/provider, version and direct processing
  launcher. Original native libraries match byte for byte.
- 538 JVM assertions passed using the weight latch decompiled from the final APK
  and the production refresh gate with a simulated Handler. Included field
  negative/zero frames, invalid/unstable frames, fresh agreement after reset,
  immutable locks, kg conversion, nonfinite values, 240,000 display requests
  coalesced to 240 renders, and recovery after a rejected Handler post.
- No Android runtime, C316H, physical RFID, scale, sustained processing,
  import/restore, or end-to-end animal release test was performed here.
- Reported field crash cause remains unconfirmed; no crash log was supplied.

## First device check — only this check initially

Install the APK and open **Sentinel Next R1** without connecting hardware or
starting a session. Confirm it reaches the cattle-processing home screen.
Do not uninstall Preview, clear its storage, change its permissions or import
over its data. Stop and report the screen/result before the next test.

After this check, validate diagnostic hardware operation under continuous reads,
then one clearly labeled test encounter and restart/export verification before
relying on this candidate for cattle processing. R1 preserves the existing two
configured read zones; it does not add the proposed four-zone automation.

## Rebuild

Set SENTINEL_APKTOOL, SENTINEL_ECJ and SENTINEL_SDK. Run `build.sh` with the
preserved baseline APK, output APK and private development keystore. The build
asserts the baseline hash before patching. Do not use an unrelated older APK.
Use ECJ/JDK with `tests/Handler.java`, `tests/RepairChecks.java`, the recovered
parser/latch in tests/, and src/.../LiveRefreshGate.java to repeat JVM checks.

Final delivered APK SHA256:
`daf9a7593a78e7cca65a9f1ab3de390aec60e38f039d050739ba550223ae720c`.
