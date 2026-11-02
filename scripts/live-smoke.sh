#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DEEPSEEK_API_KEY:?请先在本地设置DEEPSEEK_API_KEY，不要提交或发送密钥}"
java_command=java
if [[ -x .tools/jdk/Contents/Home/bin/java ]]; then
  java_command="$PWD/.tools/jdk/Contents/Home/bin/java"
fi
"$java_command" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -jar backend/target/nutrilog-agent-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=live,live-smoke --spring.main.web-application-type=none
