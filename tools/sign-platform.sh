#!/bin/bash
# 用平台签名给 APK 签名（sharedUserId=android.uid.system 必须平台签名才能装）
# 默认使用 keys/ 下的 AOSP test-keys（与该车 ROM 同套签名，指纹 C8:A2:E9:BC...）
# 用法: tools/sign-platform.sh [platform.pk8] [platform.x509.pem]
set -euo pipefail
cd "$(dirname "$0")/.."

BT="$HOME/Library/Android/sdk/build-tools/37.0.0"
PK8="${1:-keys/platform.pk8}"
CERT="${2:-keys/platform.x509.pem}"
IN=build/app-unsigned.apk
OUT=build/CarAssistant-platform.apk

[ -f "$IN" ] || { echo "先运行 tools/build.sh"; exit 1; }

if [ ! -f "$PK8" ] || [ ! -f "$CERT" ]; then
  echo "未找到平台签名文件：$PK8 / $CERT"
  echo "签名文件不随本仓库分发，请自备与你车机 ROM 同套签名的 platform.pk8 / platform.x509.pem"
  echo "用法: tools/sign-platform.sh <platform.pk8> <platform.x509.pem>"
  exit 1
fi

"$BT/apksigner" sign --key "$PK8" --cert "$CERT" --out "$OUT" "$IN"
echo "已输出: $OUT"
echo "安装: tools/install.sh"
