
#!/usr/bin/env bash
set -Eeuo pipefail

# ==========================================
# TOM TUNNEL - NATIVE ENGINE BOOTSTRAP
# ==========================================

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS="$ROOT/app/libs"
JNI="$ROOT/app/src/main/jniLibs"
TMP="${TMPDIR:-/tmp}/tomtunnel-engines"

HEV="2.18.0"
XRAY="${XRAY_VERSION:-26.11.9}"
DNSTT_REPO="https://github.com/dnstt-xyz/dnstt_xyz_app.git"
HYST_TAG="${HYST_TAG:-v1.3.5}"

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
    "$url" \
    -o "$output"

  test -s "$output" || {
    echo "ERROR: Empty download: $output" >&2
    exit 1
  }
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
# 2. XRAY DOWNLOAD
# ==========================================

XRAY_API="https://api.github.com/repos/XTLS/Xray-core/releases/tags/v${XRAY}"
XRAY_JSON="$TMP/xray-release.json"

echo "Checking Xray release: v${XRAY}"

download "$XRAY_API" "$XRAY_JSON"

fetch_xray() {
  local abi="$1"
  local asset="$2"
  local output="$JNI/$abi/libxray.so"
  local zip="$TMP/$asset.zip"
  local url

  if [ -s "$output" ]; then
    echo "Xray $abi already exists."
    return
  fi

  url="$(jq -r --arg name "$asset.zip" \
    '.assets[] | select(.name == $name) | .browser_download_url' \
    "$XRAY_JSON" | head -n1)"

  if [ -z "$url" ] || [ "$url" = "null" ]; then
    echo "ERROR: Xray asset not found: $asset.zip" >&2
    jq -r '.assets[].name' "$XRAY_JSON"
    exit 1
  fi

  download "$url" "$zip"
  unzip -t "$zip" >/dev/null

  unzip -p "$zip" xray > "$output"

  chmod 755 "$output"
  test -s "$output"

  echo "Xray $abi installed."
}

fetch_xray arm64-v8a Xray-android-arm64-v8a
fetch_xray x86_64 Xray-android-amd64

# ==========================================
# 3. DNSTT SOURCE
# ==========================================

SRC="$TMP/dnstt_xyz_app"

if [ ! -d "$SRC/.git" ]; then
  rm -rf "$SRC"
  git clone --depth 1 "$DNSTT_REPO" "$SRC"
else
  git -C "$SRC" fetch --depth 1 origin
  git -C "$SRC" reset --hard FETCH_HEAD
fi

CLIENT_DIR="$SRC/go_src/dnstt-client"

if [ ! -d "$CLIENT_DIR" ]; then
  echo "ERROR: DNSTT client directory not found."
  exit 1
fi

MOD_DIR="$CLIENT_DIR"

while [ "$MOD_DIR" != "$SRC" ] &&
      [ "$MOD_DIR" != "/" ] &&
      [ ! -f "$MOD_DIR/go.mod" ]; do
  MOD_DIR="$(dirname "$MOD_DIR")"
done

if [ ! -f "$MOD_DIR/go.mod" ]; then
  FOUND="$(find "$SRC" -name go.mod \
    -not -path '*/node_modules/*' | head -n1)"

  if [ -z "$FOUND" ]; then
    echo "ERROR: DNSTT go.mod not found."
    exit 1
  fi

  MOD_DIR="$(dirname "$FOUND")"
fi

if [ "$CLIENT_DIR" = "$MOD_DIR" ]; then
  REL="."
else
  REL="${CLIENT_DIR#"$MOD_DIR"/}"
fi

if [ "$REL" = "$CLIENT_DIR" ]; then
  echo "ERROR: Invalid DNSTT module path."
  exit 1
fi

PKG="./$REL"

echo "DNSTT module: $MOD_DIR"
echo "DNSTT package: $PKG"

build_dnstt() {
  local goarch="$1"
  local abi="$2"
  local output="$JNI/$abi/libdnstt.so"

  if [ -s "$output" ]; then
    echo "DNSTT $abi already exists."
    return
  fi

  local goos

  for goos in android linux; do

    echo "Building DNSTT: $abi ($goos)"

    if (
      cd "$MOD_DIR"

      GOOS="$goos" \
      GOARCH="$goarch" \
      CGO_ENABLED=0 \
      go build \
        -trimpath \
        -ldflags="-s -w" \
        -o "$output.tmp" \
        "$PKG"
    ); then

      mv "$output.tmp" "$output"
      chmod 755 "$output"

      test -s "$output"

      echo "DNSTT $abi compiled."
      return
    fi

    rm -f "$output.tmp"
  done

  echo "ERROR: DNSTT compilation failed: $abi"
  exit 1
}

build_dnstt arm64 arm64-v8a
build_dnstt amd64 x86_64

# ==========================================
# 4. HYSTERIA V1 - FIXED SOURCE
# ==========================================

HYST_SRC="$TMP/hysteria-v1"

if [ ! -d "$HYST_SRC/.git" ]; then

  rm -rf "$HYST_SRC"

  git clone \
    --depth 1 \
    --branch "$HYST_TAG" \
    https://github.com/apernet/hysteria.git \
    "$HYST_SRC"

else

  git -C "$HYST_SRC" fetch \
    --depth 1 origin \
    "refs/tags/$HYST_TAG:refs/tags/$HYST_TAG"

  git -C "$HYST_SRC" checkout \
    --force --detach "$HYST_TAG"

fi

# IMPORTANT:
# Hysteria v1.3.5 Go module is app/go.mod
# Main package is app/cmd/main.go

HYST_APP="$HYST_SRC/app"
HYST_CMD="$HYST_APP/cmd"

if [ ! -f "$HYST_APP/go.mod" ]; then
  echo "ERROR: Hysteria app/go.mod not found."
  exit 1
fi

if [ ! -f "$HYST_CMD/main.go" ]; then
  echo "ERROR: Hysteria app/cmd/main.go not found."
  exit 1
fi

echo "Hysteria module: $HYST_APP"
echo "Hysteria entry point: ./cmd"

build_hysteria() {
  local goarch="$1"
  local abi="$2"
  local output="$JNI/$abi/libhysteria.so"

  if [ -s "$output" ]; then
    echo "Hysteria $abi already exists."
    return
  fi

  local goos

  for goos in android linux; do

    echo "Building Hysteria: $abi ($goos)"

    rm -f "$output.tmp"

    if (
      cd "$HYST_APP"

      GOOS="$goos" \
      GOARCH="$goarch" \
      CGO_ENABLED=0 \
      go build \
        -trimpath \
        -ldflags="-s -w" \
        -o "$output.tmp" \
        ./cmd
    ); then

      mv "$output.tmp" "$output"
      chmod 755 "$output"

      test -s "$output"

      echo "Hysteria $abi compiled."
      return
    fi

    rm -f "$output.tmp"

  done

  echo "ERROR: Hysteria compilation failed: $abi"
  exit 1
}

build_hysteria arm64 arm64-v8a
build_hysteria amd64 x86_64

# ==========================================
# 5. FINAL VALIDATION
# ==========================================

for ABI in arm64-v8a x86_64; do

  for ENGINE in xray dnstt hysteria; do

    ENGINE_FILE="$JNI/$ABI/lib$ENGINE.so"

    if [ ! -s "$ENGINE_FILE" ]; then
      echo "ERROR: Missing $ENGINE_FILE"
      exit 1
    fi

    echo "Checking $ENGINE_FILE"

    file "$ENGINE_FILE"
    readelf -h "$ENGINE_FILE" >/dev/null

  done

done

unzip -t "$HEV_AAR" >/dev/null

# Remove old writable engine assets
rm -rf "$ROOT/app/src/main/assets/engines"

echo
echo "=========================================="
echo " TOM TUNNEL NATIVE ENGINES READY"
echo "=========================================="

find "$JNI" -maxdepth 2 -type f -print

echo "HEV: $HEV_AAR"
echo "XRAY: $XRAY"
echo "HYSTERIA: $HYST_TAG"

echo "=========================================="
