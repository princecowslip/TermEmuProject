#!/bin/bash
# setup_debian.sh
# Deployed by the app at first launch.
# Extracts debian-rootfs.tar.xz into internal storage, validates integrity,
# and sets POSIX permissions for PRoot execution.

set -euo pipefail

INTERNAL_DIR="${1:-$HOME/.termemu}"
ROOTFS_ARCHIVE="$(dirname "$0")/debian-rootfs.tar.xz"
ROOTFS_DIR="$INTERNAL_DIR/debian-rootfs"
STAMP="$INTERNAL_DIR/.setup_done"

echo "[setup_debian] Starting Debian rootfs setup..."

# Skip if already configured
if [[ -f "$STAMP" ]]; then
    echo "[setup_debian] Already set up. Skipping."
    exit 0
fi

# Create target directory
mkdir -p "$ROOTFS_DIR"

# Verify archive exists
if [[ ! -f "$ROOTFS_ARCHIVE" ]]; then
    echo "[setup_debian] ERROR: Rootfs archive not found at $ROOTFS_ARCHIVE"
    exit 1
fi

# Validate checksum if a .sha256 sidecar exists
CHECKSUM_FILE="${ROOTFS_ARCHIVE}.sha256"
if [[ -f "$CHECKSUM_FILE" ]]; then
    echo "[setup_debian] Verifying checksum..."
    sha256sum -c "$CHECKSUM_FILE" || { echo "[setup_debian] ERROR: Checksum mismatch!"; exit 1; }
fi

# Extract the rootfs
echo "[setup_debian] Extracting rootfs (this may take a moment)..."
tar -xJf "$ROOTFS_ARCHIVE" -C "$ROOTFS_DIR"

# Set POSIX permissions on critical binaries
chmod 755 "$ROOTFS_DIR/bin/bash" 2>/dev/null || true
chmod 755 "$ROOTFS_DIR/usr/bin/env" 2>/dev/null || true
find "$ROOTFS_DIR/bin" -type f -exec chmod 755 {} \; 2>/dev/null || true

# Create resolv.conf for DNS inside container
mkdir -p "$ROOTFS_DIR/etc"
echo "nameserver 8.8.8.8" > "$ROOTFS_DIR/etc/resolv.conf"
echo "nameserver 1.1.1.1" >> "$ROOTFS_DIR/etc/resolv.conf"

# Write setup stamp
touch "$STAMP"

echo "[setup_debian] Setup complete. Rootfs ready at: $ROOTFS_DIR"
