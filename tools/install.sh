#!/bin/bash
# 安装平台签名包到车机
# 用法: tools/install.sh [车机IP:端口]（默认 192.168.5.1:5555）
set -euo pipefail
cd "$(dirname "$0")/.."

D="${1:-192.168.5.1:5555}"
APK=build/CarAssistant-platform.apk

[ -f "$APK" ] || { echo "先运行 tools/build.sh && tools/sign-platform.sh"; exit 1; }

adb connect "$D" || true
adb -s "$D" install -r "$APK"
echo "排查日志: adb -s $D logcat -s CarAssistant"
