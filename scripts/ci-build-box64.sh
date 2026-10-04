#!/usr/bin/env bash
#
# ci-build-box64.sh — populates app/src/main/jniLibs/arm64-v8a/ BEFORE assembleDebug.
# Runs on the GitHub Actions ubuntu runner (x86_64 host, cross-building for aarch64/bionic).
#
# Produces:
#   app/src/main/jniLibs/arm64-v8a/libbox64.so     <- box64 built with the Android NDK (bionic)
#   app/src/main/jniLibs/arm64-v8a/libpayload64.so <- STATIC x86-64 ELF built with gcc-x86-64-linux-gnu
#
# IMPORTANT: Android's APK installer extracts jniLibs .so files to nativeLibraryDir WITH
# execute permission (the app manifest sets android:extractNativeLibs="true" and Gradle sets
# useLegacyPackaging=true). That is why the box64 PIE *executable* can be renamed to
# "libbox64.so" and launched via ProcessBuilder — this is the Winlator/Mobox pattern.
#
# Failure policy: if the bionic build fails after both attempts (dynarec ON then OFF),
# print the BOX64_BIONIC_BUILD_FAILED marker with the last 100 log lines and exit 1 —
# the parent decides the fallback (proot rootfs, see docs/PROGRESS.md).
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$REPO_ROOT"

JNILIBS_DIR="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
BUILD_DIR="$REPO_ROOT/build"
BOX64_SRC="$BUILD_DIR/box64-src"

log()  { echo -e "\n===================== [ci-build-box64] $* ====================="; }
die()  { echo -e "\n!!! [ci-build-box64] FATAL: $*"; exit 1; }

mkdir -p "$JNILIBS_DIR" "$BUILD_DIR"

# ---------------------------------------------------------------------------
# 1) Tooling (idempotent; runner usually has all of this already)
# ---------------------------------------------------------------------------
log "Installing host tooling (cmake, file, python3, x86-64 cross gcc)"
if command -v sudo >/dev/null 2>&1; then SUDO=sudo; else SUDO=; fi
$SUDO apt-get update -qq || true
$SUDO apt-get install -y --no-install-recommends cmake ninja-build file python3 \
    gcc-x86-64-linux-gnu libc6-dev-amd64-cross || die "apt-get install failed"

command -v cmake >/dev/null 2>&1 || die "cmake not available after install"
command -v file  >/dev/null 2>&1 || die "file not available after install"
command -v x86_64-linux-gnu-gcc >/dev/null 2>&1 || die "x86_64-linux-gnu-gcc not available"

# ---------------------------------------------------------------------------
# 2) NDK discovery: newest version under $ANDROID_HOME/ndk/<ver>, then fallbacks
# ---------------------------------------------------------------------------
log "Discovering Android NDK"
NDK_HOME=""
if [ -n "${ANDROID_HOME:-}" ] && ls -d "$ANDROID_HOME"/ndk/* >/dev/null 2>&1; then
    # lexicographic sort works for NDK version dirs (e.g. 26.3..., 27.2..., 28.0...)
    NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -n 1)"
elif [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    NDK_HOME="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_NDK_LATEST_HOME:-}" ] && [ -d "$ANDROID_NDK_LATEST_HOME" ]; then
    NDK_HOME="$ANDROID_NDK_LATEST_HOME"
fi

if [ -z "$NDK_HOME" ] || [ ! -f "$NDK_HOME/build/cmake/android.toolchain.cmake" ]; then
    echo "!!! No Android NDK with build/cmake/android.toolchain.cmake was found."
    echo "!!! ANDROID_HOME=${ANDROID_HOME:-<unset>}"
    ls -la "${ANDROID_HOME:-/nonexistent}" 2>/dev/null || true
    ls -la "${ANDROID_HOME:-/nonexistent}/ndk" 2>/dev/null || true
    ls -la /opt 2>/dev/null || true
    die "ANDROID_NDK_NOT_FOUND (see listing above)"
fi
echo "NDK selected: $NDK_HOME"

# Flexible page sizes (Android 15+ 16KB pages): opt-in flag exists since NDK r27 and is
# default ON in r28+. We only pass it on NDK >= r28 per project decision; on older NDKs
# we log a note (not fatal for the user's current device test).
NDK_VERSION_BASENAME="$(basename "$NDK_HOME")"
NDK_MAJOR="${NDK_VERSION_BASENAME%%.*}"
PAGE_SIZE_FLAG=""
if [ "${NDK_MAJOR:-0}" -ge 28 ] 2>/dev/null; then
    PAGE_SIZE_FLAG="-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
    echo "NDK r$NDK_MAJOR detected: enabling ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES."
else
    echo "NOTE: NDK $NDK_VERSION_BASENAME (< r28) — 16KB flexible-page-sizes flag not passed."
    echo "NOTE: binaries may be 4KB-aligned only; acceptable for this PoC, revisit before Android 15+ testing."
fi

# ---------------------------------------------------------------------------
# 3) Clone upstream box64 (never committed to this repo — decision D5)
# ---------------------------------------------------------------------------
log "Cloning ptitSeb/box64 (main) into $BOX64_SRC"
rm -rf "$BOX64_SRC"
git clone --depth 1 --branch main https://github.com/ptitSeb/box64.git "$BOX64_SRC" \
    || die "box64 clone failed"
BOX64_COMMIT="$(git -C "$BOX64_SRC" rev-parse --short HEAD || echo unknown)"
echo "box64 commit: $BOX64_COMMIT"

# ---------------------------------------------------------------------------
# 4) Build box64 for bionic (PIE executable, arm64-v8a, android-28)
#    Attempt 1: dynarec ON (JIT — what we want in production)
#    Attempt 2: dynarec OFF (pure interpreter — acceptable for the PoC) + NOLOADADDR=ON
# ---------------------------------------------------------------------------
build_box64() { # $1: extra cmake args, $2: log file
    local extra="$1" logfile="$2"
    rm -rf "$BUILD_DIR/box64-build"
    mkdir -p "$BUILD_DIR/box64-build"
    echo "cmake args: $extra"
    cmake -S "$BOX64_SRC" -B "$BUILD_DIR/box64-build" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK_HOME/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-28 \
        -DANDROID=ON \
        -DCMAKE_BUILD_TYPE=RelWithDebInfo \
        $PAGE_SIZE_FLAG \
        $extra > "$logfile" 2>&1 || { tail -n 60 "$logfile"; return 1; }
    make -C "$BUILD_DIR/box64-build" -j"$(nproc)" >> "$logfile" 2>&1 || { tail -n 60 "$logfile"; return 1; }
    return 0
}

DYNAREC_MODE="OFF"
LOG1="$BUILD_DIR/box64-build-dynarec-on.log"
LOG2="$BUILD_DIR/box64-build-dynarec-off.log"

log "Building box64 with NDK/bionic — attempt 1: ARM_DYNAREC=ON"
if build_box64 "-DARM_DYNAREC=ON" "$LOG1"; then
    DYNAREC_MODE="ON"
    echo "RESULT: box64 built with ARM_DYNAREC=ON (dynarec)"
else
    echo "!!! attempt 1 (ARM_DYNAREC=ON) failed — see $LOG1"
    log "Retrying with ARM_DYNAREC=OFF (interpreter mode) + NOLOADADDR=ON"
    if build_box64 "-DARM_DYNAREC=OFF -DNOLOADADDR=ON" "$LOG2"; then
        DYNAREC_MODE="OFF"
        echo "RESULT: box64 built with ARM_DYNAREC=OFF (interpreter fallback)"
    else
        echo "!!! attempt 2 (ARM_DYNAREC=OFF) failed — see $LOG2"
        echo "--- last 100 lines of the last build log ---"
        tail -n 100 "$LOG2" || true
        echo "BOX64_BIONIC_BUILD_FAILED"
        exit 1
    fi
fi

# Locate the produced box64 executable (normally $BUILD_DIR/box64-build/box64)
BOX64_BIN="$BUILD_DIR/box64-build/box64"
if [ ! -x "$BOX64_BIN" ]; then
    BOX64_BIN="$(find "$BUILD_DIR/box64-build" -maxdepth 2 -type f -name box64 -perm -111 | head -n 1 || true)"
fi
[ -n "$BOX64_BIN" ] && [ -x "$BOX64_BIN" ] || { echo "BOX64_BIONIC_BUILD_FAILED"; die "box64 executable not found after build"; }

echo "--- file(1) on box64 ---"
file "$BOX64_BIN" || true
echo "--- readelf header ---"
(readelf -h "$BOX64_BIN" 2>/dev/null | grep -E 'Class|Machine|Type') || true

cp -f "$BOX64_BIN" "$JNILIBS_DIR/libbox64.so" || die "failed to install libbox64.so into jniLibs"
chmod 755 "$JNILIBS_DIR/libbox64.so" || die "chmod 755 failed on libbox64.so"
echo "Installed: $JNILIBS_DIR/libbox64.so (dynarec=$DYNAREC_MODE, commit=$BOX64_COMMIT)"

# ---------------------------------------------------------------------------
# 5) Build the STATIC x86-64 payload (built independently here, even if a
#    sibling Makefile exists in payloads/x86_64/)
# ---------------------------------------------------------------------------
log "Building static x86-64 payload"
PAYLOAD_SRC="$REPO_ROOT/payloads/x86_64/hello_static.c"
[ -f "$PAYLOAD_SRC" ] || die "payload source not found: $PAYLOAD_SRC"
PAYLOAD_BIN="$BUILD_DIR/hello_static.x86_64"
x86_64-linux-gnu-gcc -static -O2 -Wall -Wextra -o "$PAYLOAD_BIN" "$PAYLOAD_SRC" \
    || { echo "PAYLOAD_BUILD_FAILED"; die "payload cross-compile failed"; }

echo "--- file(1) on payload ---"
file "$PAYLOAD_BIN" || true

# Sanity: must be a statically linked x86-64 ELF
file "$PAYLOAD_BIN" | grep -q "x86-64" || { echo "PAYLOAD_BUILD_FAILED"; die "payload is not x86-64?!"; }
file "$PAYLOAD_BIN" | grep -qi "statically linked" || { echo "PAYLOAD_BUILD_FAILED"; die "payload is not statically linked?!"; }

cp -f "$PAYLOAD_BIN" "$JNILIBS_DIR/libpayload64.so" || die "failed to install libpayload64.so into jniLibs"
chmod 755 "$JNILIBS_DIR/libpayload64.so" || die "chmod 755 failed on libpayload64.so"
echo "Installed: $JNILIBS_DIR/libpayload64.so"

# ---------------------------------------------------------------------------
# 6) Final evidence
# ---------------------------------------------------------------------------
log "jniLibs summary"
ls -la "$JNILIBS_DIR"
for f in "$JNILIBS_DIR"/*.so; do
    echo "== $f"; file "$f"; done
echo "SUMMARY: box64 dynarec=$DYNAREC_MODE commit=$BOX64_COMMIT ndk=$NDK_VERSION_BASENAME"
log "DONE"
exit 0
