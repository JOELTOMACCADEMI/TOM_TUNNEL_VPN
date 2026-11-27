
#!/usr/bin/env bash
set -Eeuo pipefail

# ==========================================
# TOM TUNNEL - ANDROID NATIVE ENGINE BUILDER
# ==========================================

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS="$ROOT/app/libs"
JNI="$ROOT/app/src/main/jniLibs"
TMP="${TMPDIR:-/tmp}/tomtunnel-engines"

HEV="2.18.0"
XRAY="${XRAY_VERSION:-26.11.9}"
DNSTT_REPO="https://github.com/dnstt-xyz/dnstt_xyz_app.git"
HYST_TAG="${HYST_TAG:-v1.3.5}"
ANDROID_API="${ANDROID_API:-24}"

mkdir -p "$LIBS" "$JNI/arm64-v8a" "$JNI/x86_64" "$TMP"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: Missing command: $1" >&2
    exit 1
  }
}

need curl
need unzip
need git
need go
need jq
need file
need readelf

download() {
  local url="$1"
  local output="$2"

  echo "Downloading: $url"

  curl -fL \
    --retry 5 \
    --retry-all-errors \
    --retry-delay 3 \
    --connect-timeout 30 \
    --max-time 600 \
    "$url" -o "$output"

  test -s "$output"
}

# ==========================================
# ANDROID NDK
# ==========================================

NDK="${ANDROID_NDK_HOME:-}"

if [ -z "$NDK" ]; then
  NDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}/ndk/27.2.12479018"
fi

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

test -d "$TOOLCHAIN" || {
  echo "ERROR: Android NDK not found: $NDK"
  exit 1
}

echo "Android NDK: $NDK"
echo "Default Go: $(go version)"

# ==========================================
# COMMON ANDROID COMPILER
# ==========================================

android_build() {
  local arch="$1"
  local package="$2"
  local output="$3"
  local go_bin="${4:-go}"
  local compiler

  case "$arch" in
    arm64)
      compiler="aarch64-linux-android${ANDROID_API}-clang"
      ;;
    amd64)
      compiler="x86_64-linux-android${ANDROID_API}-clang"
      ;;
    *)
      echo "ERROR: Unsupported architecture: $arch"
      return 1
      ;;
  esac

  test -x "$TOOLCHAIN/$compiler" || {
    echo "ERROR: Missing compiler: $compiler"
    return 1
  }

  rm -f "$output.tmp"

  echo "Compiler: $("$go_bin" version)"
  echo "Target: Android $arch"

  (
    export CGO_ENABLED=1
    export GOOS=android
    export GOARCH="$arch"
    export CC="$TOOLCHAIN/$compiler"

    "$go_bin" build \
      -buildmode=pie \
      -trimpath \
      -ldflags="-s -w" \
      -o "$output.tmp" \
      "$package"
  )

  test -s "$output.tmp"

  mv "$output.tmp" "$output"
  chmod 755 "$output"

  echo "Compiled: $output"
}

# ==========================================
# 1. HEV SOCKS5 TUNNEL
# ==========================================

HEV_AAR="$LIBS/hev-socks5-tunnel.aar"

if [ ! -s "$HEV_AAR" ]; then
  HEV_URL="https://github.com/heiher/hev-socks5-tunnel/releases/download/${HEV}/hev-socks5-tunnel.aar"
  download "$HEV_URL" "$HEV_AAR"
fi

unzip -t "$HEV_AAR" >/dev/null

echo "HEV AAR verified."

# ==========================================
# 2. XRAY
# ==========================================

XRAY_API="https://api.github.com/repos/XTLS/Xray-core/releases/tags/v${XRAY}"
XRAY_JSON="$TMP/xray-release.json"

download "$XRAY_API" "$XRAY_JSON"

fetch_xray() {
  local abi="$1"
  local asset="$2"
  local output="$JNI/$abi/libxray.so"
  local zip="$TMP/$asset.zip"
  local url

  rm -f "$output"

  url="$(jq -r --arg name "$asset.zip" \
    '.assets[] | select(.name == $name) | .browser_download_url' \
    "$XRAY_JSON" | head -n1)"

  if [ -z "$url" ] || [ "$url" = "null" ]; then
    echo "ERROR: Xray asset missing: $asset.zip"
    exit 1
  fi

  download "$url" "$zip"
  unzip -t "$zip" >/dev/null

  unzip -p "$zip" xray > "$output"

  chmod 755 "$output"
  test -s "$output"

  echo "Xray installed: $abi"
}

fetch_xray arm64-v8a Xray-android-arm64-v8a
fetch_xray x86_64 Xray-android-amd64

# ==========================================
# 3. DNSTT
# ==========================================

SRC="$TMP/dnstt_xyz_app"

rm -rf "$SRC"

git clone --depth 1 "$DNSTT_REPO" "$SRC"

CLIENT_DIR="$SRC/go_src/dnstt-client"

test -d "$CLIENT_DIR" || {
  echo "ERROR: DNSTT client directory missing."
  exit 1
}

MOD_DIR="$CLIENT_DIR"

while [ "$MOD_DIR" != "$SRC" ] &&
      [ "$MOD_DIR" != "/" ] &&
      [ ! -f "$MOD_DIR/go.mod" ]; do
  MOD_DIR="$(dirname "$MOD_DIR")"
done

if [ ! -f "$MOD_DIR/go.mod" ]; then
  echo "ERROR: DNSTT go.mod missing."
  exit 1
fi

if [ "$CLIENT_DIR" = "$MOD_DIR" ]; then
  PKG="."
else
  PKG="./${CLIENT_DIR#"$MOD_DIR"/}"
fi

echo "DNSTT module: $MOD_DIR"
echo "DNSTT package: $PKG"

build_dnstt() {
  local arch="$1"
  local abi="$2"
  local output="$JNI/$abi/libdnstt.so"

  rm -f "$output"

  echo "Building DNSTT: $abi"

  (
    cd "$MOD_DIR"
    android_build "$arch" "$PKG" "$output" go
  )
}

build_dnstt arm64 arm64-v8a
build_dnstt amd64 x86_64

# ==========================================
# 4. HYSTERIA V1 - GO 1.20.14
# ==========================================

HYST_SRC="$TMP/hysteria-v1"
HYST_APP="$HYST_SRC/app"
HYST_GO="${HYST_GO:-}"

if [ -z "$HYST_GO" ] || [ ! -x "$HYST_GO" ]; then
  echo "ERROR: HYST_GO must point to Go 1.20.14."
  exit 1
fi

HYST_GO_VERSION="$("$HYST_GO" version)"

echo "Hysteria Go: $HYST_GO_VERSION"

if [[ "$HYST_GO_VERSION" != *"go1.20.14"* ]]; then
  echo "ERROR: Hysteria requires Go 1.20.14."
  exit 1
fi

rm -rf "$HYST_SRC"

git clone \
  --depth 1 \
  --branch "$HYST_TAG" \
  https://github.com/apernet/hysteria.git \
  "$HYST_SRC"

test -f "$HYST_APP/go.mod" || {
  echo "ERROR: Hysteria app/go.mod missing."
  exit 1
}

test -f "$HYST_APP/cmd/main.go" || {
  echo "ERROR: Hysteria app/cmd/main.go missing."
  exit 1
}

echo "Hysteria module: $HYST_APP"

build_hysteria() {
  local arch="$1"
  local abi="$2"
  local output="$JNI/$abi/libhysteria.so"

  rm -f "$output"

  echo "Building Hysteria: $abi"

  (
    cd "$HYST_APP"
    android_build "$arch" "./cmd" "$output" "$HYST_GO"
  )

  echo "Hysteria compiled: $abi"
}

build_hysteria arm64 arm64-v8a
build_hysteria amd64 x86_64

# ==========================================
# 5. VALIDATE ELF ARCHITECTURES
# ==========================================

validate_elf() {
  local path="$1"
  local expected="$2"
  local actual

  test -s "$path" || {
    echo "ERROR: Missing binary: $path"
    exit 1
  }

  actual="$(readelf -h "$path" |
    awk -F: '/Machine:/ {
      gsub(/^[ \t]+/, "", $2);
      print $2
    }')"

  echo "$path : $actual"

  case "$expected" in
    arm64)
      [[ "$actual" == *AArch64* ]] || {
        echo "ERROR: Incorrect ARM64 architecture."
        exit 1
      }
      ;;
    amd64)
      [[ "$actual" == *X86-64* ]] || {
        echo "ERROR: Incorrect x86_64 architecture."
        exit 1
      }
      ;;
  esac

  file "$path"
}

for ENGINE in xray dnstt hysteria; do
  validate_elf "$JNI/arm64-v8a/lib$ENGINE.so" arm64
  validate_elf "$JNI/x86_64/lib$ENGINE.so" amd64
done

unzip -t "$HEV_AAR" >/dev/null

# ==========================================
# 6. FINAL REPORT
# ==========================================

echo
echo "=========================================="
echo " TOM TUNNEL NATIVE ENGINES READY"
echo "=========================================="

find "$JNI" -maxdepth 2 -type f -print

echo "HEV: $HEV"
echo "XRAY: $XRAY"
echo "DNSTT: Go 1.24"
echo "HYSTERIA: $HYST_TAG / Go 1.20.14"
echo "=========================================="
