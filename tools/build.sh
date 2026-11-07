#!/bin/bash
# CarAssistant 手工编译链（无 Gradle）：aapt2 -> javac -> d8 -> apksigner
# 用法: tools/build.sh
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/37.0.0"
PLATFORM_JAR="$SDK/platforms/android-37.0/android.jar"
SRC=app/src/main
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/obj" "$OUT/dex"

echo "[1/5] aapt2 compile resources"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "[2/5] aapt2 link"
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" \
  -I "$PLATFORM_JAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  "$OUT/res.zip"

echo "[3/5] javac"
find "$SRC/java" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 \
  -classpath "$PLATFORM_JAR" \
  -d "$OUT/obj" @"$OUT/sources.txt" 2>&1 | grep -v "deprecat\|obsolete" || true

echo "[4/5] d8"
"$BT/d8" --min-api 28 --lib "$PLATFORM_JAR" \
  --output "$OUT/dex" $(find "$OUT/obj" -name '*.class')

echo "[5/5] 打包 + debug 签名（仅供安装测试，无系统权限）"
( cd "$OUT/dex" && zip -q -X ../app-unsigned.apk classes.dex )
cp "$OUT/app-unsigned.apk" "$OUT/app-debug.apk"
if [ ! -f "$OUT/debug.keystore" ]; then
  keytool -genkeypair -keystore "$OUT/debug.keystore" \
    -storepass android -alias androiddebugkey -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$OUT/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android "$OUT/app-debug.apk"

echo
echo "完成："
echo "  $OUT/app-unsigned.apk  未签名包 —— 下一步运行 tools/sign-platform.sh"
echo "  $OUT/app-debug.apk     debug 签名 —— 只能测试界面，车辆接口会因权限被拒"
