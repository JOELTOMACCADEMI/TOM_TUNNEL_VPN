#!/usr/bin/env bash
set -Eeuo pipefail

# ==========================================================
# TOM TUNNEL - ANDROID NATIVE ENGINE BUILDER
# Builds Xray, DNSTT and Hysteria for ARM64 and x86_64.
# Downloads the HEV SOCKS5 Tunnel AAR.
# ==========================================================

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS="$ROOT/app/libs"
JNI="$ROOT/app/src/main/jniLibs"
TMP="${TMPDIR:-/tmp}/tomtunnel-engines"

HEV="${HEV_VERSION:-2.18.0}"
XRAY="${XRAY_VERSION:-26.11.9}"
DNSTT_REPO="${DNSTT_REPO:-https://github.com/dnstt-xyz/dnstt_xyz_app.git}"
HYST_TAG="${HYST_TAG:-v1.3.5}"
# HEV SOCKS5 Tunnel AAR 2.18.0 requires Android API 29 or newer.
ANDROID_API="${ANDROID_API:-29}"

mkdir -p "$LIBS" "$JNI/arm64-v8a" "$JNI/x86_64" "$TMP"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: Required command not found: $1" >&2
    exit 1
  }
}

need curl
need unzip
need git
need go
need file
need readelf
need awk

download() {
  local url="$1"
  local output="$2"
  local -a headers=()

  echo "Downloading: $url"

  # Only attach a token to api.github.com requests.
  # Public release asset downloads do not need API authentication.
  if [[ "$url" == https://api.github.com/* ]] && [[ -n "${GITHUB_TOKEN:-}" ]]; then
    headers+=(
      -H "Authorization: Bearer ${GITHUB_TOKEN}"
      -H "Accept: application/vnd.github+json"
      -H "X-GitHub-Api-Version: 2022-11-28"
    )
  fi

  rm -f "$output"
  curl -fL \
    --retry 5 \
    --retry-all-errors \
    --retry-delay 5 \
    --connect-timeout 30 \
    --max-time 900 \
    -H "User-Agent: TOM-TUNNEL-CI" \
    "${headers[@]}" \
    "$url" \
    -o "$output"

  test -s "$output" || {
    echo "ERROR: Downloaded file is empty: $output" >&2
    return 1
  }
}

# ==========================================================
# ANDROID NDK
# ==========================================================

NDK="${ANDROID_NDK_HOME:-}"

if [[ -z "$NDK" ]]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [[ -n "$SDK" ]] || {
    echo "ERROR: ANDROID_HOME / ANDROID_SDK_ROOT is not set." >&2
    exit 1
  }
  NDK="$SDK/ndk/27.2.12479018"
fi

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

[[ -d "$TOOLCHAIN" ]] || {
  echo "ERROR: Android NDK toolchain not found: $TOOLCHAIN" >&2
  exit 1
}

ARM_CC="$TOOLCHAIN/aarch64-linux-android${ANDROID_API}-clang"
X86_CC="$TOOLCHAIN/x86_64-linux-android${ANDROID_API}-clang"

[[ -x "$ARM_CC" ]] || {
  echo "ERROR: Missing Android ARM64 compiler: $ARM_CC" >&2
  exit 1
}
[[ -x "$X86_CC" ]] || {
  echo "ERROR: Missing Android x86_64 compiler: $X86_CC" >&2
  exit 1
}

echo "Android NDK: $NDK"
echo "Android native API: $ANDROID_API"
echo "Default Go: $(go version)"

# ==========================================================
# COMMON ANDROID GO COMPILER
# ==========================================================

android_build() {
  local arch="$1"
  local package="$2"
  local output="$3"
  local go_bin="${4:-go}"
  local compiler

  case "$arch" in
    arm64)
      compiler="$ARM_CC"
      ;;
    amd64)
      compiler="$X86_CC"
      ;;
    *)
      echo "ERROR: Unsupported Go architecture: $arch" >&2
      return 1
      ;;
  esac

  [[ -x "$compiler" ]] || {
    echo "ERROR: Android compiler is not executable: $compiler" >&2
    return 1
  }

  rm -f "$output" "$output.tmp"

  echo "Go compiler: $("$go_bin" version)"
  echo "Target: Android $arch (API $ANDROID_API)"
  echo "Package: $package"

  (
    export CGO_ENABLED=1
    export GOOS=android
    export GOARCH="$arch"
    export CC="$compiler"
    export CGO_CFLAGS="-O2"
    export CGO_LDFLAGS="-Wl,-z,max-page-size=16384"

    "$go_bin" build \
      -buildmode=pie \
      -trimpath \
      -ldflags="-s -w" \
      -o "$output.tmp" \
      "$package"
  )

  [[ -s "$output.tmp" ]] || {
    echo "ERROR: Go produced no output: $output.tmp" >&2
    return 1
  }

  mv "$output.tmp" "$output"
  chmod 755 "$output"
  echo "Compiled: $output"
}

# ==========================================================
# 1. HEV SOCKS5 TUNNEL AAR
# ==========================================================

HEV_AAR="$LIBS/hev-socks5-tunnel.aar"
HEV_URL="https://github.com/heiher/hev-socks5-tunnel/releases/download/${HEV}/hev-socks5-tunnel.aar"

if [[ ! -s "$HEV_AAR" ]] || ! unzip -t "$HEV_AAR" >/dev/null 2>&1; then
  download "$HEV_URL" "$HEV_AAR"
fi

unzip -t "$HEV_AAR" >/dev/null
echo "HEV AAR verified: $HEV_AAR"

# ==========================================================
# 2. XRAY CORE
# Avoid the GitHub Releases API here: direct asset URLs avoid
# API rate-limit/403 failures for this public release.
# ==========================================================

fetch_xray() {
  local abi="$1"
  local asset="$2"
  local output="$JNI/$abi/libxray.so"
  local zip="$TMP/$asset.zip"
  local url="https://github.com/XTLS/Xray-core/releases/download/v${XRAY}/${asset}.zip"

  rm -f "$output" "$zip"

  download "$url" "$zip"
  unzip -t "$zip" >/dev/null

  if ! unzip -Z1 "$zip" | grep -qx 'xray'; then
    echo "ERROR: Archive does not contain the expected 'xray' binary: $asset.zip" >&2
    unzip -Z1 "$zip" >&2
    return 1
  fi

  unzip -p "$zip" xray > "$output"
  chmod 755 "$output"
  [[ -s "$output" ]] || {
    echo "ERROR: Xray binary is empty for $abi" >&2
    return 1
  }

  echo "Xray installed: $abi"
}

fetch_xray arm64-v8a Xray-android-arm64-v8a
fetch_xray x86_64 Xray-android-amd64

# ==========================================================
# 3. DNSTT CLIENT
# ==========================================================

SRC="$TMP/dnstt_xyz_app"
CLIENT_DIR="$SRC/go_src/dnstt-client"

rm -rf "$SRC"
git clone --depth 1 "$DNSTT_REPO" "$SRC"

[[ -d "$CLIENT_DIR" ]] || {
  echo "ERROR: DNSTT client directory not found: $CLIENT_DIR" >&2
  exit 1
}

MOD_DIR="$CLIENT_DIR"
while [[ "$MOD_DIR" != "$SRC" ]] &&
      [[ "$MOD_DIR" != "/" ]] &&
      [[ ! -f "$MOD_DIR/go.mod" ]]; do
  MOD_DIR="$(dirname "$MOD_DIR")"
done

[[ -f "$MOD_DIR/go.mod" ]] || {
  echo "ERROR: Could not find go.mod for DNSTT." >&2
  exit 1
}

if [[ "$CLIENT_DIR" == "$MOD_DIR" ]]; then
  DNSTT_PACKAGE="."
else
  DNSTT_PACKAGE="./${CLIENT_DIR#"$MOD_DIR"/}"
fi

echo "DNSTT module: $MOD_DIR"
echo "DNSTT package: $DNSTT_PACKAGE"

build_dnstt() {
  local arch="$1"
  local abi="$2"
  local output="$JNI/$abi/libdnstt.so"

  rm -f "$output"
  echo "Building DNSTT: $abi"

  (
    cd "$MOD_DIR"
    android_build "$arch" "$DNSTT_PACKAGE" "$output" go
  )
}

build_dnstt arm64 arm64-v8a
build_dnstt amd64 x86_64

# ==========================================================
# 4. HYSTERIA V1 - GO 1.20.14
# ==========================================================

HYST_SRC="$TMP/hysteria-v1"
HYST_APP="$HYST_SRC/app"
HYST_GO="${HYST_GO:-}"

[[ -n "$HYST_GO" && -x "$HYST_GO" ]] || {
  echo "ERROR: HYST_GO must point to the Go 1.20.14 executable." >&2
  exit 1
}

HYST_GO_VERSION="$("$HYST_GO" version)"
echo "Hysteria Go: $HYST_GO_VERSION"

[[ "$HYST_GO_VERSION" == *"go1.20.14"* ]] || {
  echo "ERROR: Hysteria build requires Go 1.20.14." >&2
  exit 1
}

rm -rf "$HYST_SRC"
git clone \
  --depth 1 \
  --branch "$HYST_TAG" \
  https://github.com/apernet/hysteria.git \
  "$HYST_SRC"

[[ -f "$HYST_APP/go.mod" ]] || {
  echo "ERROR: Hysteria app/go.mod not found." >&2
  exit 1
}

[[ -f "$HYST_APP/cmd/main.go" ]] || {
  echo "ERROR: Hysteria app/cmd/main.go not found." >&2
  exit 1
}

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

# ==========================================================
# 5. VALIDATE ELF FILES AND ARCHITECTURES
# ==========================================================

validate_elf() {
  local path="$1"
  local expected="$2"
  local actual

  [[ -s "$path" ]] || {
    echo "ERROR: Missing or empty native library: $path" >&2
    exit 1
  }

  actual="$(
    readelf -h "$path" |
      awk -F: '/Machine:/ {
        gsub(/^[ \t]+/, "", $2);
        print $2
      }'
  )"

  echo "$path : $actual"

  case "$expected" in
    arm64)
      [[ "$actual" == *AArch64* ]] || {
        echo "ERROR: Expected ARM64/AArch64 binary: $path" >&2
        exit 1
      }
      ;;
    amd64)
      [[ "$actual" == *X86-64* ]] || {
        echo "ERROR: Expected x86_64 binary: $path" >&2
        exit 1
      }
      ;;
    *)
      echo "ERROR: Unknown expected architecture: $expected" >&2
      exit 1
      ;;
  esac

  file "$path"
}

for engine in xray dnstt hysteria; do
  validate_elf "$JNI/arm64-v8a/lib${engine}.so" arm64
  validate_elf "$JNI/x86_64/lib${engine}.so" amd64
done

unzip -t "$HEV_AAR" >/dev/null

# ==========================================================
# 6. FINAL REPORT
# ==========================================================

echo
echo "=================================================="
echo " TOM TUNNEL NATIVE ENGINES READY"
echo "=================================================="
find "$JNI" -maxdepth 2 -type f -print | sort
echo "HEV: $HEV"
echo "XRAY: $XRAY"
echo "DNSTT: repository $DNSTT_REPO"
echo "HYSTERIA: $HYST_TAG / Go 1.20.14"
echo "Android native API: $ANDROID_API"
echo "=================================================="
