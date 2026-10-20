#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
NDK_DIR="${NDK:-${SDK_DIR}/ndk/29.0.14206865}"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
WORK="$PROJECT_DIR/build/native-runtime"
NATIVE_OUT="$PROJECT_DIR/lorie-app/build/generated/prootJniLibs/arm64-v8a"
ASSET_OUT="$PROJECT_DIR/lorie-app/build/generated/prootAssets/licenses"
PROOT_VERSION="5.1.107.96"
TALLOC_VERSION="2.5.0"
PROOT_SHA="75f654fe60dea92dabff2bf083ae8bfe4f91baa6a1a374786a6bf391015eebaa"
TALLOC_SHA="912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007"

export CC="$TOOLCHAIN/aarch64-linux-android24-clang"
export CXX="$TOOLCHAIN/aarch64-linux-android24-clang++"
export AR="$TOOLCHAIN/llvm-ar"
export RANLIB="$TOOLCHAIN/llvm-ranlib"
export STRIP="$TOOLCHAIN/llvm-strip"
export OBJCOPY="$TOOLCHAIN/llvm-objcopy"
export OBJDUMP="$TOOLCHAIN/llvm-objdump"

if [[ ! -x "$CC" ]]; then
    echo "Missing Android NDK compiler: $CC" >&2
    exit 1
fi

mkdir -p "$WORK/downloads" "$NATIVE_OUT" "$ASSET_OUT"
STATIC_ROOT="$WORK/static-$TALLOC_VERSION"
mkdir -p "$STATIC_ROOT/include" "$STATIC_ROOT/lib"

download_checked() {
    local url="$1" target="$2" expected="$3"
    if [[ ! -f "$target" ]] || ! printf '%s  %s\n' "$expected" "$target" | sha256sum --check --status; then
        curl --fail --location --retry 3 "$url" --output "$target.part"
        printf '%s  %s\n' "$expected" "$target.part" | sha256sum --check
        mv "$target.part" "$target"
    fi
}

download_checked "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" \
    "$WORK/downloads/talloc-$TALLOC_VERSION.tar.gz" "$TALLOC_SHA"
download_checked "https://github.com/termux/proot/archive/v$PROOT_VERSION.zip" \
    "$WORK/downloads/proot-$PROOT_VERSION.zip" "$PROOT_SHA"

TALLOC_SRC="$WORK/talloc-$TALLOC_VERSION"
PROOT_SRC="$WORK/proot-$PROOT_VERSION"
if [[ ! -d "$TALLOC_SRC" ]]; then
    tar -xzf "$WORK/downloads/talloc-$TALLOC_VERSION.tar.gz" -C "$WORK"
fi
if [[ ! -d "$PROOT_SRC" ]]; then
    unzip -q "$WORK/downloads/proot-$PROOT_VERSION.zip" -d "$WORK"
fi

# PRoot v5.1.107.96 uses string functions here without including their header.
python3 - "$PROOT_SRC/src/extension/ashmem_memfd/ashmem_memfd.c" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text()
if "#include <string.h>" not in source:
    marker = "#include <stdlib.h>"
    if marker not in source:
        raise SystemExit("Unexpected PRoot ashmem_memfd.c layout")
    path.write_text(source.replace(marker, marker + "\n#include <string.h>", 1))
PY

if [[ ! -f "$STATIC_ROOT/lib/libtalloc.a" ]]; then
    mkdir -p "$WORK/tool-shims"
    printf '#!/bin/sh\nexit 1\n' > "$WORK/tool-shims/pkg-config"
    chmod +x "$WORK/tool-shims/pkg-config"
    ln -sf "$(command -v python3)" "$WORK/tool-shims/python"

    cat > "$TALLOC_SRC/cross-answers.txt" <<'ANSWERS'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
ANSWERS

    (
        cd "$TALLOC_SRC"
        export LD="$TOOLCHAIN/ld.lld"
        export PATH="$WORK/tool-shims:$PATH"
        export CFLAGS="-O2 -fPIC"
        ./configure --prefix="$STATIC_ROOT" --disable-rpath --disable-python \
            --cross-compile --cross-answers=cross-answers.txt
        PYTHONHASHSEED=1 python3 ./buildtools/bin/waf build --targets=talloc
        "$AR" rcs "$STATIC_ROOT/lib/libtalloc.a" bin/default/talloc*.o
        cp talloc.h "$STATIC_ROOT/include/talloc.h"
    )
fi

(
    cd "$PROOT_SRC/src"
    if ! command -v gawk >/dev/null 2>&1; then
        echo "Missing GNU awk. Install it with: apt-get install gawk" >&2
        exit 1
    fi
    mkdir -p "$WORK/proot-tool-shims"
    ln -sf "$(command -v gawk)" "$WORK/proot-tool-shims/awk"
    export PATH="$WORK/proot-tool-shims:$PATH"
    export CFLAGS="-I$STATIC_ROOT/include -Werror=implicit-function-declaration"
    export CPPFLAGS="-DARG_MAX=131072"
    export LDFLAGS="-static -L$STATIC_ROOT/lib -Wl,-z,noexecstack -Wl,-z,max-page-size=16384"
    make -j"${JOBS:-2}" V=1 \
        BINDIR="$WORK/package/bin" \
        PROOT_UNBUNDLE_LOADER="$WORK/package/libexec/proot" install
)

install -m 0755 "$WORK/package/bin/proot" "$NATIVE_OUT/libproot.so"
install -m 0755 "$WORK/package/libexec/proot/loader" "$NATIVE_OUT/libproot_loader.so"
if [[ -f "$WORK/package/libexec/proot/loader32" ]]; then
    install -m 0755 "$WORK/package/libexec/proot/loader32" "$NATIVE_OUT/libproot_loader32.so"
fi

for binary in "$NATIVE_OUT"/*.so; do
    "$STRIP" "$binary"
    if "$TOOLCHAIN/llvm-readelf" -l "$binary" | grep -q INTERP; then
        echo "Unexpected dynamic interpreter in $binary" >&2
        exit 1
    fi
    file "$binary"
done

for spec in "proot:$PROOT_SRC" "talloc:$TALLOC_SRC"; do
    name="${spec%%:*}"
    source="${spec#*:}"
    for license in COPYING COPYING.LIB LICENSE; do
        if [[ -f "$source/$license" ]]; then
            cp "$source/$license" "$ASSET_OUT/$name-$license"
        fi
    done
done
printf 'PRoot %s: https://github.com/termux/proot/tree/v%s\ntalloc %s: https://www.samba.org/ftp/talloc/talloc-%s.tar.gz\n' \
    "$PROOT_VERSION" "$PROOT_VERSION" "$TALLOC_VERSION" "$TALLOC_VERSION" > "$ASSET_OUT/SOURCES.txt"
echo "Standalone ARM64 PRoot runtime prepared."
