#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "$(uname -s)" != Darwin || "$(uname -m)" != x86_64 ]]; then
  echo '此脚本仅适用于Intel Mac，请在其他平台安装JDK21和Maven3.9.9。' >&2
  exit 1
fi
mkdir -p .tools
if [[ ! -x .tools/jdk/Contents/Home/bin/java ]]; then
  curl --fail --location --retry 3 --continue-at - --max-time 1800 \
    https://corretto.aws/downloads/resources/21.0.13.0.11/amazon-corretto-21.0.13.0.11-macosx-x64.tar.gz \
    --output .tools/jdk21.tar.gz
  mkdir -p .tools/jdk-unpack
  tar -xzf .tools/jdk21.tar.gz -C .tools/jdk-unpack
  jdk_bundle=$(find .tools/jdk-unpack -maxdepth 1 -type d -name '*.jdk' -print -quit)
  test -n "$jdk_bundle"
  mv "$jdk_bundle" .tools/jdk
fi
if [[ ! -x .tools/apache-maven-3.9.9/bin/mvn ]]; then
  curl --fail --location --retry 3 \
    https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.tar.gz \
    --output .tools/maven.tar.gz
  tar -xzf .tools/maven.tar.gz -C .tools
fi
export JAVA_HOME="$PWD/.tools/jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PWD/.tools/apache-maven-3.9.9/bin:$PATH"
java -version
mvn -Dmaven.repo.local="$PWD/.tools/m2" -f backend/pom.xml test
