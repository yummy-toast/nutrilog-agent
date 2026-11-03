#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DEEPSEEK_API_KEY:?请先在本地设置DEEPSEEK_API_KEY，不要提交或发送密钥}"
smoke_profile="${1:-live-smoke}"
case "$smoke_profile" in
  live-smoke|food-agent-smoke) ;;
  *) echo '仅支持live-smoke或food-agent-smoke' >&2; exit 1 ;;
esac
java_command=java
if [[ -x .tools/jdk/Contents/Home/bin/java ]]; then
  java_command="$PWD/.tools/jdk/Contents/Home/bin/java"
fi
"$java_command" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -jar backend/target/nutrilog-agent-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active="live,$smoke_profile" --spring.main.web-application-type=none
