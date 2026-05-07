#!/usr/bin/env bash
# Regenerate the gobley-uniffi-bindgen test fixtures.
#
# This is a manual / one-shot script — it is NOT a `build.rs` and is NOT run
# at `cargo test` time. The four binary fixtures are committed to git so
# tests stay hermetic. Re-run this only when:
#   - the dummy crate workspace changes
#   - the pinned `uniffi` version changes
#   - the marker symbol layout (`ffi_<crate>_uniffi_contract_version`) changes
#
# Usage:
#   ./build.sh            # build every target the host can produce
#   ./build.sh wasm dylib # build a subset (names match cases below)
#
# Targets and the toolchains they need:
#   wasm   wasm32-unknown-unknown                 (rustup target only)
#   dylib  aarch64-apple-darwin / x86_64-apple-darwin (macOS host)
#   so     x86_64-unknown-linux-gnu               (rustup + cross linker)
#   dll    x86_64-pc-windows-gnu                  (rustup + mingw-w64)
#
# On a clean macOS host you can install everything with:
#   rustup target add wasm32-unknown-unknown x86_64-apple-darwin \
#                     x86_64-unknown-linux-gnu x86_64-pc-windows-gnu
#   brew install messense/macos-cross-toolchains/x86_64-unknown-linux-gnu \
#                mingw-w64 wasm-tools llvm
#
# Verification: every produced artefact must export both
#   ffi_dummy_a_uniffi_contract_version
#   ffi_dummy_b_uniffi_contract_version
# (plus ~55 other UniFFI scaffolding symbols per crate; the markers are the
# ones the future `read_exports` function keys off).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DUMMY_DIR="${SCRIPT_DIR}/dummy_crate"
OUT_DIR="${SCRIPT_DIR}"

# Native macOS host arch — switch to x86_64-apple-darwin if you're on Intel.
DARWIN_TARGET="${DARWIN_TARGET:-aarch64-apple-darwin}"
LINUX_TARGET="x86_64-unknown-linux-gnu"
WIN_TARGET="x86_64-pc-windows-gnu"
WASM_TARGET="wasm32-unknown-unknown"

# Cross-compiler binaries. Override via env if your prefixes differ.
LINUX_LINKER="${LINUX_LINKER:-x86_64-unknown-linux-gnu-gcc}"
LINUX_STRIP="${LINUX_STRIP:-x86_64-unknown-linux-gnu-strip}"
WIN_LINKER="${WIN_LINKER:-x86_64-w64-mingw32-gcc}"
WIN_STRIP="${WIN_STRIP:-x86_64-w64-mingw32-strip}"
WASM_TOOLS="${WASM_TOOLS:-wasm-tools}"

cd "${DUMMY_DIR}"

TARGETS=()
[[ $# -gt 0 ]] && TARGETS=("$@")

want() {
    if [[ ${#TARGETS[@]} -eq 0 ]]; then return 0; fi
    for t in "${TARGETS[@]}"; do [[ "$t" == "$1" ]] && return 0; done
    return 1
}

VALID_TARGETS=(wasm dylib so dll)
for t in "${TARGETS[@]:-}"; do
    [[ -z "$t" ]] && continue
    valid=0
    for v in "${VALID_TARGETS[@]}"; do [[ "$t" == "$v" ]] && valid=1 && break; done
    if [[ $valid -eq 0 ]]; then
        echo "error: unknown target '$t'. valid: ${VALID_TARGETS[*]}" >&2
        exit 1
    fi
done

build_wasm() {
    echo "==> wasm"
    cargo build -p dummy_combined --release --target "${WASM_TARGET}"
    "${WASM_TOOLS}" strip \
        "target/${WASM_TARGET}/release/dummy_combined.wasm" \
        -o "${OUT_DIR}/dummy.wasm"
}

build_dylib() {
    echo "==> dylib (${DARWIN_TARGET})"
    cargo build -p dummy_combined --release --target "${DARWIN_TARGET}"
    cp "target/${DARWIN_TARGET}/release/libdummy_combined.dylib" \
       "${OUT_DIR}/dummy.dylib"
    # `strip -x` keeps global (exported) symbols; we only want to drop locals.
    strip -x "${OUT_DIR}/dummy.dylib"
}

build_so() {
    echo "==> so (${LINUX_TARGET})"
    if ! command -v "${LINUX_LINKER}" >/dev/null; then
        echo "  skip: ${LINUX_LINKER} not on PATH"
        return 0
    fi
    CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER="${LINUX_LINKER}" \
        cargo build -p dummy_combined --release --target "${LINUX_TARGET}"
    "${LINUX_STRIP}" \
        "target/${LINUX_TARGET}/release/libdummy_combined.so" \
        -o "${OUT_DIR}/dummy.so"
}

build_dll() {
    echo "==> dll (${WIN_TARGET})"
    if ! command -v "${WIN_LINKER}" >/dev/null; then
        echo "  skip: ${WIN_LINKER} not on PATH"
        return 0
    fi
    CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER="${WIN_LINKER}" \
        cargo build -p dummy_combined --release --target "${WIN_TARGET}"
    "${WIN_STRIP}" \
        "target/${WIN_TARGET}/release/dummy_combined.dll" \
        -o "${OUT_DIR}/dummy.dll"
}

if want wasm  || [[ ${#TARGETS[@]} -eq 0 ]]; then build_wasm;  fi
if want dylib || [[ ${#TARGETS[@]} -eq 0 ]]; then build_dylib; fi
if want so    || [[ ${#TARGETS[@]} -eq 0 ]]; then build_so;    fi
if want dll   || [[ ${#TARGETS[@]} -eq 0 ]]; then build_dll;   fi

echo
echo "Fixtures written to: ${OUT_DIR}"
ls -la "${OUT_DIR}"/dummy.* 2>/dev/null || true
