#!/usr/bin/env bash
#
# build-light-client-android.sh — M1 of the embedded light-client spike.
#
# Cross-compiles upstream `ckb-light-client` (SQLite backend) for Android via
# cargo-ndk, producing standalone binaries the app can later ship as a native
# library and exec as a subprocess. Clones the pinned upstream source into a
# gitignored build dir if absent. Builds the BIN crate (RPC server built-in),
# `--no-default-features --features sqlite` so neither RocksDB (C++) nor OpenSSL
# is pulled — the two things that make Android NDK builds painful.
#
# Usage:  scripts/build-light-client-android.sh [arm64-v8a|x86_64|both]
#         (default: both)
#
set -euo pipefail

# ── Config ───────────────────────────────────────────────────────────────────
REPO_URL="https://github.com/nervosnetwork/ckb-light-client.git"
PIN_TAG="v0.5.5-rc1"          # first release with the SQLite backend
API_LEVEL=26                  # matches the app's minSdk
WHICH="${1:-both}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="${REPO_ROOT}/build/ckb-light-client-src"

# Locate an Android NDK (prefer the newest installed).
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  for n in /home/phill/Android/Sdk/ndk/27.2.* /home/phill/Android/Sdk/ndk/27.1.* /home/phill/Android/Sdk/ndk/*; do
    [[ -d "$n" ]] && export ANDROID_NDK_HOME="$n" && break
  done
fi
[[ -d "${ANDROID_NDK_HOME:-/nonexistent}" ]] || { echo "ERROR: no Android NDK found; set ANDROID_NDK_HOME"; exit 1; }
echo "==> NDK: $ANDROID_NDK_HOME"

# ── Rust targets ─────────────────────────────────────────────────────────────
case "$WHICH" in
  arm64-v8a) ABIS=(arm64-v8a);          TRIPLES=(aarch64-linux-android) ;;
  x86_64)    ABIS=(x86_64);             TRIPLES=(x86_64-linux-android) ;;
  both)      ABIS=(arm64-v8a x86_64);   TRIPLES=(aarch64-linux-android x86_64-linux-android) ;;
  *) echo "ERROR: unknown ABI '$WHICH' (use arm64-v8a | x86_64 | both)"; exit 1 ;;
esac
echo "==> ensuring rust targets: ${TRIPLES[*]}"
rustup target add "${TRIPLES[@]}" >/dev/null

# NDK r23+ removed libgcc.a (replaced by libunwind), but the toolchain still
# requests `-lgcc` when linking Rust binaries → "unable to find library -lgcc".
# Canonical fix: a libgcc.a linker script that redirects -lgcc to -lunwind.
SHIM_DIR="${REPO_ROOT}/build/ndk-shim"
mkdir -p "$SHIM_DIR"
printf 'INPUT(-lunwind)\n' > "$SHIM_DIR/libgcc.a"
export RUSTFLAGS="${RUSTFLAGS:-} -L ${SHIM_DIR}"
echo "==> libgcc→libunwind shim: $SHIM_DIR"

# ── Source ───────────────────────────────────────────────────────────────────
if [[ ! -d "$SRC_DIR/.git" ]]; then
  echo "==> cloning $REPO_URL @ $PIN_TAG"
  git clone --depth 1 --branch "$PIN_TAG" "$REPO_URL" "$SRC_DIR"
else
  echo "==> source present: $SRC_DIR ($(git -C "$SRC_DIR" describe --tags --always 2>/dev/null || echo unknown))"
fi

# ── Build ────────────────────────────────────────────────────────────────────
cd "$SRC_DIR"
for i in "${!ABIS[@]}"; do
  abi="${ABIS[$i]}"
  echo ""
  echo "==> building ckb-light-client for $abi (api $API_LEVEL, sqlite, no rocksdb/openssl)…"
  # arm64 is the real device target → must succeed; x86_64 (emulator) is best-effort.
  if cargo ndk -t "$abi" --platform "$API_LEVEL" build --release --bin ckb-light-client \
        --no-default-features --features sqlite; then
    echo "==> $abi: build OK"
  else
    rc=$?
    if [[ "$abi" == "arm64-v8a" ]]; then echo "ERROR: arm64 build failed (rc=$rc)"; exit $rc; fi
    echo "WARN: $abi build failed (rc=$rc) — continuing (emulator target, non-blocking)"
  fi
done

# ── Artifact facts ───────────────────────────────────────────────────────────
echo ""
echo "==================== ARTIFACT FACTS ===================="
for triple in "${TRIPLES[@]}"; do
  bin="$SRC_DIR/target/$triple/release/ckb-light-client"
  echo "--- $triple ---"
  if [[ ! -f "$bin" ]]; then echo "  (not built)"; continue; fi
  ls -lh "$bin" | awk '{print "  size: "$5}'
  file "$bin" | sed 's/^/  file: /'
  # Confirm Android linkage + audit for the deps we explicitly excluded.
  if command -v readelf >/dev/null; then
    echo "  NEEDED:"; readelf -d "$bin" 2>/dev/null | grep NEEDED | sed 's/^/    /' || echo "    (static / none)"
  fi
  echo -n "  openssl symbols: "; { strings -a "$bin" | grep -ciE "openssl|libssl|BoringSSL" || true; }
  echo -n "  rocksdb symbols: "; { strings -a "$bin" | grep -ciE "rocksdb" || true; }
  echo -n "  sqlite present:  "; { strings -a "$bin" | grep -ciE "sqlite3" || true; }
done
echo "========================================================"

# Stage the arm64 binary as a strippable native library (Android execs binaries
# only from the read-only nativeLibraryDir, so it must ship named lib*.so).
ARM_BIN="$SRC_DIR/target/aarch64-linux-android/release/ckb-light-client"
if [[ -f "$ARM_BIN" ]]; then
  STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
  STAGED="$REPO_ROOT/build/libckblightclient.so"
  cp "$ARM_BIN" "$STAGED"
  [[ -x "$STRIP" ]] && "$STRIP" --strip-all "$STAGED" && echo "==> staged + stripped: $STAGED ($(ls -lh "$STAGED" | awk '{print $5}'))"
fi

echo "Done. Next (M2): push the staged lib to a device's nativeLibraryDir, run on-device, drive set_scripts + get_cells."
