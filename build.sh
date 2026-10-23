#!/usr/bin/env bash
# 可复现构建：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 用法: ./build.sh [输出目录，默认 dist]
set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$ROOT/dist}
AJ=${ANDROID_JAR:-/root/run/android-sdk/android.jar}
D8=${D8_JAR:-/root/run/android-sdk/d8.jar}
APKSIGNER=${APKSIGNER_JAR:-/root/run/android-tools/usr/share/java/apksigner.jar}
KS=${KEYSTORE:-/root/opt/keystore.jks}

# 冷热分离：热区缺工具链时从冷仓拉取
if [ -f /root/run/env.sh ]; then source /root/run/env.sh >/dev/null 2>&1 || true; fi
if [ ! -f "$AJ" ] || [ ! -f "$D8" ]; then
  echo "缺少工具链：$AJ 或 $D8"
  echo "冷仓拉取: dev pull jdk android-tools android-sdk"
  exit 1
fi

VERSION=$(grep -o 'android:versionName="[^"]*"' AndroidManifest.xml | head -1 | sed 's/.*="//;s/"//')
CODE=$(grep -o 'android:versionCode="[^"]*"' AndroidManifest.xml | head -1 | sed 's/.*="//;s/"//')
NAME="stepbridge-lite-$VERSION"
B=$ROOT/build
[ -d "$B" ] && rm -r -f "$B"
mkdir -p "$B/gen" "$B/obj" "$B/dex" "$OUT"

cat <<INFO
=== StepBridge X build ===
version : $VERSION ($CODE)
javac   : $(javac -version 2>&1)
aapt2   : $(command -v aapt2 || echo missing)
min-sdk : 24  target-sdk: 34
out     : $OUT/$NAME.apk
INFO

echo '[1/7] aapt2 compile res'
if [ -d res ]; then aapt2 compile --dir res -o "$B/res.zip"; fi

echo '[2/7] aapt2 link'
if [ -f "$B/res.zip" ]; then
  aapt2 link -o "$B/app-unsigned.apk" -I "$AJ" --manifest AndroidManifest.xml -R "$B/res.zip" --java "$B/gen" -A assets --auto-add-overlay --min-sdk-version 24 --target-sdk-version 34
else
  aapt2 link -o "$B/app-unsigned.apk" -I "$AJ" --manifest AndroidManifest.xml --java "$B/gen" -A assets --min-sdk-version 24 --target-sdk-version 34
fi

echo '[3/7] javac'
javac -source 8 -target 8 -nowarn -bootclasspath "$AJ" -d "$B/obj" $(find src "$B/gen" -name '*.java')

echo '[4/7] d8 dex'
java -cp "$D8" com.android.tools.r8.D8 --lib "$AJ" --min-api 24 --output "$B/dex" $(find "$B/obj" -name '*.class')

echo '[5/7] zip classes.dex'
(cd "$B/dex" && zip -q "$B/app-unsigned.apk" classes.dex)

echo '[6/7] zipalign'
zipalign -f 4 "$B/app-unsigned.apk" "$B/app-aligned.apk"

echo '[7/7] sign'
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass stepclaw -keypass stepclaw -alias stepclaw -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=StepBridge,OU=Dev,O=Local,L=NA,ST=NA,C=CN" >/dev/null 2>&1
fi
java -jar "$APKSIGNER" sign --ks "$KS" --ks-pass pass:stepclaw --key-pass pass:stepclaw --out "$OUT/$NAME.apk" "$B/app-aligned.apk"

echo "OK  $OUT/$NAME.apk"
ls -l "$OUT/$NAME.apk"
