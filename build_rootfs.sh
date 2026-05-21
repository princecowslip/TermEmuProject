#!/data/data/com.termux/files/usr/bin/bash
# build_rootfs.sh
# Fetches the official Debian trixie arm64 rootfs from Docker Hub and
# packages it as the debian-rootfs.tar.xz asset expected by the Android app.
#
# Requires: curl, tar, jq, xz-utils  (apt install curl tar jq xz-utils)
# Must be run on an arm64 device (or cross-build environment).

set -euo pipefail

DEBIAN_VERSION="trixie"
TARGET_ARCH="arm64"          # the architecture we build for
TARGET_DIR="$HOME/debian-bootstrap"
# Resolve the output path relative to this script so it works from any CWD.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/app/src/main/assets"

echo "[*] Creating workspace at $TARGET_DIR ..."
mkdir -p "$TARGET_DIR" "$OUTPUT_DIR"
cd "$TARGET_DIR"

# 1. Install minimal tools
apt install -y curl tar jq xz-utils

# 2. Obtain a Docker Hub pull token for the debian repository
echo "[*] Fetching Docker Hub auth token..."
TOKEN=$(curl -fsSL \
    "https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/debian:pull" \
    | jq -r '.token')

REGISTRY="https://registry-1.docker.io/v2/library/debian"

# 3. Fetch the multi-arch manifest list and extract the arm64 manifest digest.
#    The top-level manifest for a tag is a "fat" manifest list; we must select
#    the entry whose platform.architecture == "arm64".
echo "[*] Resolving arm64 manifest digest for debian:${DEBIAN_VERSION}..."
ARM64_DIGEST=$(curl -fsSL \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.docker.distribution.manifest.list.v2+json" \
    "${REGISTRY}/manifests/${DEBIAN_VERSION}" \
    | jq -r '.manifests[]
             | select(.platform.architecture == "'"${TARGET_ARCH}"'"
                      and .platform.os == "linux")
             | .digest' \
    | head -1)

if [[ -z "$ARM64_DIGEST" ]]; then
    echo "[!] ERROR: Could not find arm64 manifest for debian:${DEBIAN_VERSION}"
    exit 1
fi
echo "[*] arm64 manifest digest: $ARM64_DIGEST"

# 4. Fetch the arm64-specific image manifest to get its layer digests
echo "[*] Fetching arm64 image manifest..."
LAYER_DIGEST=$(curl -fsSL \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.docker.distribution.manifest.v2+json" \
    "${REGISTRY}/manifests/${ARM64_DIGEST}" \
    | jq -r '.layers[0].digest')

if [[ -z "$LAYER_DIGEST" ]]; then
    echo "[!] ERROR: Could not extract layer digest from arm64 manifest"
    exit 1
fi
echo "[*] Rootfs layer digest: $LAYER_DIGEST"

# 5. Download the root filesystem layer (a gzip-compressed tar)
echo "[*] Downloading rootfs layer (may be several hundred MB)..."
curl -fsSL -o layer.tar.gz \
    -H "Authorization: Bearer $TOKEN" \
    "${REGISTRY}/blobs/${LAYER_DIGEST}"

# 6. Extract into a clean rootfs directory
echo "[*] Extracting base filesystem..."
rm -rf rootfs
mkdir  rootfs
tar -xf layer.tar.gz -C rootfs

# 7. Android-compatibility tweaks
echo "[*] Applying Android compatibility tweaks..."
cd rootfs

# Set Google Public DNS so the container can resolve names
mkdir -p etc
echo "nameserver 8.8.8.8"  > etc/resolv.conf
echo "nameserver 1.1.1.1" >> etc/resolv.conf
echo "127.0.0.1 localhost"  > etc/hosts

# Trim unnecessary bulk to keep the asset size manageable
rm -rf usr/share/doc/* var/cache/apt/archives/* 2>/dev/null || true

cd ..

# 8. Repack as xz-compressed tar for the assets directory
echo "[*] Compressing into debian-rootfs.tar.xz (this may take several minutes)..."
tar -cJf "${OUTPUT_DIR}/debian-rootfs.tar.xz" -C rootfs .

echo ""
echo "[+] Success!"
echo "    Asset written to: ${OUTPUT_DIR}/debian-rootfs.tar.xz"
echo "    $(du -sh "${OUTPUT_DIR}/debian-rootfs.tar.xz" | cut -f1) on disk"
