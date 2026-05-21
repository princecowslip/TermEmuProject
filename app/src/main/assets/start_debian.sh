#!/bin/bash
# start_debian.sh
# Core script that launches the PRoot-based Debian Linux environment.
# Binds virtual mounts for /proc, /sys, /dev, and /tmp, then executes bash.

set -euo pipefail

INTERNAL_DIR="${1:-$HOME/.termemu}"
ROOTFS_DIR="$INTERNAL_DIR/debian-rootfs"
PROOT_BIN="$(dirname "$0")/proot"

# Ensure proot is executable
chmod +x "$PROOT_BIN" 2>/dev/null || true

if [[ ! -d "$ROOTFS_DIR" ]]; then
    echo "[start_debian] ERROR: Rootfs not found. Run setup_debian.sh first."
    exit 1
fi

echo "[start_debian] Launching Debian PRoot environment..."

exec "$PROOT_BIN" \
    --rootfs="$ROOTFS_DIR" \
    --bind=/dev \
    --bind=/dev/urandom \
    --bind=/proc \
    --bind=/sys \
    --bind=/tmp \
    --bind="$INTERNAL_DIR/home:/root" \
    --pwd=/root \
    --kill-on-exit \
    /bin/bash --login

# exec falls through only on error
echo "[start_debian] ERROR: PRoot exited unexpectedly."
exit 1
