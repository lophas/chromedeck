#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Build a Debian ARM64 runtime with browser dependencies on an x86_64 Linux host.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$PROJECT_DIR/build/debian-runtime-v1"
ROOTFS="$WORK/rootfs"
DOWNLOADS="$WORK/downloads"
OUT="$PROJECT_DIR/build/runtime-image"
BASE_COMMIT="cf1f4a45447842b45e9952e0f018ae734a7341c7"
BASE_SHA256="df9831efb49cab24dec437e8482f2a9ddbd9e239131fa3ffe095dc1492081e1d"
BASE_URL="https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/$BASE_COMMIT/trixie/oci/blobs/rootfs.tar.gz"
RELEASE_URL="https://github.com/lophas/chromedeck/releases/download/debian-runtime-v1"
CHROME_REPO="https://dl.google.com/linux/chrome/deb"

for tool in proot python3 curl tar gzip dpkg-deb sha256sum; do
    if ! command -v "$tool" >/dev/null; then
        echo "Missing build tool: $tool" >&2
        exit 1
    fi
done
QEMU="$(command -v qemu-aarch64-static || command -v qemu-aarch64 || true)"
if [[ -z "$QEMU" ]]; then
    echo "Missing ARM64 emulator. Install it with: apt-get install qemu-user-static" >&2
    exit 1
fi

mkdir -p "$WORK" "$DOWNLOADS" "$OUT" "$ROOTFS"

download_checked() {
    local url="$1" path="$2" expected="$3"
    if [[ ! -f "$path" ]] || ! printf '%s  %s\n' "$expected" "$path" | sha256sum --check --status; then
        curl --fail --location --retry 3 "$url" --output "$path.part"
        printf '%s  %s\n' "$expected" "$path.part" | sha256sum --check
        mv "$path.part" "$path"
    fi
}

download_checked "$BASE_URL" "$DOWNLOADS/debian-base.tar.gz" "$BASE_SHA256"
if [[ ! -f "$WORK/base-ready" ]]; then
    tar -xzf "$DOWNLOADS/debian-base.tar.gz" -C "$ROOTFS" --no-same-owner
    touch "$WORK/base-ready"
fi

# Use the official ARM64 repository metadata to pin Chrome's URL and checksum.
curl --fail --location --retry 3 \
    "$CHROME_REPO/dists/stable/main/binary-arm64/Packages.gz" \
    --output "$DOWNLOADS/chrome-Packages.gz"

python3 - "$DOWNLOADS/chrome-Packages.gz" "$WORK/chrome.json" "$CHROME_REPO" <<'PY'
import gzip
import json
import sys

with gzip.open(sys.argv[1], "rt", encoding="utf-8") as source:
    text = source.read()
for paragraph in text.split("\n\n"):
    fields = {}
    last_key = None
    for line in paragraph.splitlines():
        if line.startswith((" ", "\t")) and last_key:
            fields[last_key] += " " + line.strip()
        elif ": " in line:
            last_key, value = line.split(": ", 1)
            fields[last_key] = value
    if fields.get("Package") == "google-chrome-stable" and fields.get("Architecture") == "arm64":
        metadata = {
            "package": fields["Package"],
            "version": fields["Version"],
            "url": sys.argv[3].rstrip("/") + "/" + fields["Filename"].lstrip("/"),
            "sha256": fields["SHA256"],
        }
        with open(sys.argv[2], "w", encoding="utf-8") as output:
            json.dump(metadata, output, indent=2)
        break
else:
    raise SystemExit("The official repository contains no google-chrome-stable ARM64 package.")
PY

CHROME_URL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["url"])' "$WORK/chrome.json")"
CHROME_SHA="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sha256"])' "$WORK/chrome.json")"
CHROME_VERSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$WORK/chrome.json")"
CHROME_DEB="$DOWNLOADS/google-chrome-stable-arm64.deb"
download_checked "$CHROME_URL" "$CHROME_DEB" "$CHROME_SHA"
if [[ "$(dpkg-deb --field "$CHROME_DEB" Architecture)" != arm64 ]]; then
    echo "Chrome package architecture is not arm64." >&2
    exit 1
fi

# A tiny local metapackage asks apt to resolve Chrome's actual alternatives and
# version requirements, without placing Google's browser in the published image.
DEPS_DIR="$WORK/runtime-deps"
mkdir -p "$DEPS_DIR/DEBIAN"
DEPS="$(dpkg-deb --field "$CHROME_DEB" Depends)"
cat > "$DEPS_DIR/DEBIAN/control" <<CONTROL
Package: desktop-chrome-runtime-deps
Version: 1
Architecture: arm64
Maintainer: Desktop Chrome Android <root@localhost>
Depends: $DEPS
Description: Runtime dependencies for Desktop Chrome Android
CONTROL
dpkg-deb --build --root-owner-group "$DEPS_DIR" "$WORK/runtime-deps.deb"

mkdir -p "$ROOTFS/tmp" "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys"
mkdir -p "$ROOTFS/root" "$ROOTFS/usr/local/bin" "$ROOTFS/usr/sbin"
if [[ -L "$ROOTFS/etc/resolv.conf" ]]; then
    unlink "$ROOTFS/etc/resolv.conf"
fi
cp -L /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
cp "$WORK/runtime-deps.deb" "$ROOTFS/tmp/runtime-deps.deb"
printf '#!/bin/sh\nexit 101\n' > "$ROOTFS/usr/sbin/policy-rc.d"
chmod 0755 "$ROOTFS/usr/sbin/policy-rc.d"

guest() {
    PROOT_NO_SECCOMP=1 proot -0 -q "$QEMU" -r "$ROOTFS" \
        -b /dev -b /proc -b /sys -w / \
        /usr/bin/env -i HOME=/root USER=root LOGNAME=root \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        LANG=C.UTF-8 DEBIAN_FRONTEND=noninteractive "$@"
}

echo "Checking the ARM64 Debian environment..."
guest /usr/bin/dpkg --print-architecture
guest /bin/cat /etc/debian_version

if [[ ! -f "$WORK/dependencies-ready" ]] || [[ "$(cat "$WORK/dependencies-ready")" != "$CHROME_VERSION" ]]; then
    guest /usr/bin/apt-get -o APT::Sandbox::User=root update
    guest /usr/bin/apt-get -o APT::Sandbox::User=root -o Dpkg::Use-Pty=0 \
        install -y --no-install-recommends \
        /tmp/runtime-deps.deb openbox dbus-x11 x11-utils xauth xkb-data \
        fonts-dejavu-core fonts-liberation fonts-noto-color-emoji \
        ca-certificates curl xz-utils procps
    guest /usr/bin/apt-get check
    printf '%s\n' "$CHROME_VERSION" > "$WORK/dependencies-ready"
fi

cat > "$ROOTFS/usr/local/bin/desktop-chrome-session" <<'SESSION'
#!/bin/sh
set -eu
export HOME=/root
export XDG_CONFIG_HOME=/root/.config
mkdir -p "$XDG_CONFIG_HOME" /root/Downloads
openbox --sm-disable &
wm_pid=$!
trap 'kill "$wm_pid" 2>/dev/null || true' EXIT HUP INT TERM
/opt/google/chrome/chrome \
    --no-sandbox \
    --disable-dev-shm-usage \
    --disable-gpu \
    --ozone-platform=x11 \
    --password-store=basic \
    --user-data-dir=/root/.config/desktop-chrome-android \
    --no-first-run \
    --no-default-browser-check \
    --start-maximized \
    "$@"
SESSION
chmod 0755 "$ROOTFS/usr/local/bin/desktop-chrome-session"

guest /usr/bin/dpkg-query -W '-f=${Package}\t${Version}\n' > "$OUT/debian-runtime-v1.packages.txt"
guest /usr/bin/apt-get clean

# All cleanup paths refer to files created inside this build's rootfs.
python3 - "$ROOTFS" <<'PY'
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1])
for name in ("var/lib/apt/lists", "var/log"):
    directory = root / name
    if directory.is_dir() and not directory.is_symlink():
        for entry in directory.iterdir():
            if entry.is_dir() and not entry.is_symlink():
                shutil.rmtree(entry)
            else:
                entry.unlink()
(root / "tmp/runtime-deps.deb").unlink(missing_ok=True)
for name in ("etc/machine-id", "var/lib/dbus/machine-id", "etc/resolv.conf"):
    path = root / name
    if path.is_symlink():
        path.unlink()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("")
PY

IMAGE="$OUT/debian-runtime-v1-arm64.tar.gz"
echo "Compressing the Debian runtime..."
tar -C "$ROOTFS" --numeric-owner --owner=0 --group=0 -cf - . | gzip -1 > "$IMAGE.part"
mv "$IMAGE.part" "$IMAGE"

python3 - "$IMAGE" "$WORK/chrome.json" "$OUT/runtime-manifest.json" "$RELEASE_URL" "$BASE_COMMIT" "$ROOTFS" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

image, chrome_file, manifest_file = map(Path, sys.argv[1:4])
digest = hashlib.sha256()
with image.open("rb") as source:
    while chunk := source.read(1024 * 1024):
        digest.update(chunk)
unpacked = sum(p.stat().st_size for p in Path(sys.argv[6]).rglob("*")
               if p.is_file() and not p.is_symlink())
manifest = {
    "schema": 1,
    "image_version": 1,
    "architecture": "arm64",
    "distribution": "Debian 13 (trixie)",
    "image_url": sys.argv[4] + "/" + image.name,
    "image_sha256": digest.hexdigest(),
    "image_size": image.stat().st_size,
    "unpacked_size": unpacked,
    "base_source_commit": sys.argv[5],
    "chrome": json.loads(chrome_file.read_text()),
}
manifest_file.write_text(json.dumps(manifest, indent=2) + "\n")
print(manifest_file.read_text())
PY

ls -lh "$IMAGE" "$OUT/runtime-manifest.json"
echo "Debian ARM64 runtime image is ready."
