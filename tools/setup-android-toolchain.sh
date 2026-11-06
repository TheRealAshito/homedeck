#!/usr/bin/env bash
# Installs the Homedeck Android build toolchain (Temurin JDK 17, Android
# cmdline-tools + platform 34, Gradle 8.8) into $TOOLS_DIR (default ~/tools).
# Idempotent: re-running skips anything already installed.
set -euo pipefail

TOOLS_DIR="${TOOLS_DIR:-$HOME/tools}"
SDK_DIR="$TOOLS_DIR/android-sdk"
JDK_DIR="$TOOLS_DIR/jdk17"
GRADLE_VERSION="8.8"

mkdir -p "$TOOLS_DIR"
cd "$TOOLS_DIR"

if [ ! -x "$JDK_DIR/bin/java" ]; then
  echo "== Downloading Temurin JDK 17 =="
  curl -sSL -o jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  rm -rf jdk-extract
  mkdir jdk-extract
  tar xzf jdk17.tar.gz -C jdk-extract
  rm -rf "$JDK_DIR"
  mv jdk-extract/jdk-* "$JDK_DIR"
  rm -rf jdk-extract jdk17.tar.gz
fi

export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "== Downloading Android cmdline-tools =="
  curl -sSL -o cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  rm -rf cmdline-extract
  python3 -m zipfile -e cmdline-tools.zip cmdline-extract
  chmod +x cmdline-extract/cmdline-tools/bin/* cmdline-extract/cmdline-tools/lib/* 2>/dev/null || true
  mkdir -p "$SDK_DIR/cmdline-tools"
  rm -rf "$SDK_DIR/cmdline-tools/latest"
  mv cmdline-extract/cmdline-tools "$SDK_DIR/cmdline-tools/latest"
  rm -rf cmdline-extract cmdline-tools.zip
fi

if [ ! -x "$TOOLS_DIR/gradle-$GRADLE_VERSION/bin/gradle" ]; then
  echo "== Downloading Gradle $GRADLE_VERSION =="
  curl -sSL -o gradle.zip "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  python3 -m zipfile -e gradle.zip .
  chmod +x "gradle-$GRADLE_VERSION/bin/gradle"
  rm -f gradle.zip
fi

echo "== Installing Android SDK packages (platform-tools, android-34, build-tools 34.0.0) =="
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
# sdkmanager misparses empty proxy variables ("no protocol: "), so drop them.
for v in HTTP_PROXY HTTPS_PROXY http_proxy https_proxy; do
  if [ -z "${!v:-}" ]; then unset "$v"; fi
done
SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --sdk_root="$SDK_DIR" "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null

echo "== Toolchain ready =="
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
"$TOOLS_DIR/gradle-$GRADLE_VERSION/bin/gradle" --version 2>/dev/null | grep -i "^Gradle" || true
ls "$SDK_DIR/platforms" "$SDK_DIR/build-tools"
