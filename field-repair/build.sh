#!/usr/bin/env bash
set -euo pipefail
# Usage: build.sh preserved-preview.apk output.apk signing.keystore
# Android SDK build tools 34; Apktool 2.11.1; ECJ 3.38.0; JDK/JRE 17.
: "${SENTINEL_APKTOOL:?Path to apktool jar}"
: "${SENTINEL_ECJ:?Path to ECJ jar}"
: "${SENTINEL_SDK:?Path to Android SDK}"
repair_root="$(cd -- "$(dirname -- "$0")" && pwd)"
sentinel_build="$(mktemp -d)"
trap 'rm -rf -- "$sentinel_build"' EXIT
python3 - "$1" <<'PY'
import hashlib,sys
assert hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest() == '2d6058b085835f682e598b80d0e38ceeb1b652d240a53a034d79de3966600c83', 'Unexpected Preview baseline'
PY
java -jar "$SENTINEL_APKTOOL" d -f "$1" -o "$sentinel_build/app"
python3 "$repair_root/patch_preview.py" "$sentinel_build/app"
java -jar "$SENTINEL_ECJ" -8 -cp "$SENTINEL_SDK/platforms/android-34/android.jar" -d "$sentinel_build/classes" "$repair_root"/src/com/herdmate/tagreapersentinel/*.java
python3 - "$sentinel_build" <<'PY'
import pathlib,sys,zipfile
p=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(p/'helpers.jar','w') as out:
 for f in (p/'classes').rglob('*.class'): out.write(f,str(f.relative_to(p/'classes')))
PY
"$SENTINEL_SDK/build-tools/34.0.0/d8" --min-api 24 --lib "$SENTINEL_SDK/platforms/android-34/android.jar" --output "$sentinel_build/helpers.zip" "$sentinel_build/helpers.jar"
python3 - "$sentinel_build" "$1" <<'PY'
import pathlib,sys,zipfile
p=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(sys.argv[2]) as baseline, zipfile.ZipFile(p/'helpers.zip') as dex, zipfile.ZipFile(p/'helper.apk','w') as out:
 for name in ('AndroidManifest.xml','resources.arsc'): out.writestr(name,baseline.read(name))
 out.writestr('classes.dex',dex.read('classes.dex'))
PY
java -jar "$SENTINEL_APKTOOL" d -r -f "$sentinel_build/helper.apk" -o "$sentinel_build/helpers"
cp "$sentinel_build"/helpers/smali/com/herdmate/tagreapersentinel/*.smali "$sentinel_build/app/smali_classes3/com/herdmate/tagreapersentinel/"
cp "$repair_root/smali/NextLiveRefresh.smali" "$sentinel_build/app/smali_classes3/com/herdmate/tagreapersentinel/"
java -jar "$SENTINEL_APKTOOL" b "$sentinel_build/app" -o "$sentinel_build/unsigned.apk"
"$SENTINEL_SDK/build-tools/34.0.0/zipalign" -p -f 4 "$sentinel_build/unsigned.apk" "$sentinel_build/aligned.apk"
# Development key only. Never commit the keystore to the repository.
"$SENTINEL_SDK/build-tools/34.0.0/apksigner" sign --ks "$3" --ks-key-alias sentinel-next --ks-pass pass:android --key-pass pass:android --out "$2" "$sentinel_build/aligned.apk"
"$SENTINEL_SDK/build-tools/34.0.0/apksigner" verify --verbose "$2"
"$SENTINEL_SDK/build-tools/34.0.0/zipalign" -c -p 4 "$2"
