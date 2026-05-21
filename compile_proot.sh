#!/data/data/com.termux/files/usr/bin/bash
set -e

echo "[*] Installing compilation toolchain..."
apt install -y git make clang pkg-config libtalloc2

# Clone the PRoot repository modified for Android compatibility
echo "[*] Cloning PRoot source code..."
if [ ! -d "proot" ]; then
    git clone https://github.com/proot-me/proot.git
fi
cd proot/src

# We must compile it statically so it doesn't rely on Termux's internal linker path (/data/data/com.termux...)
# This ensures it runs inside the Android app's sandboxed private storage path
echo "[*] Compiling static PRoot binary..."
make LDFLAGS="-static" CARE_LDFLAGS="-static"

# Copy the compiled binary directly into your Android assets directory
echo "[*] Copying binary to assets..."
cp proot "$HOME/TermEmuProject/app/src/main/assets/proot"

echo "[+] Success! Compiled static proot binary ready for Android 16 deployment."
