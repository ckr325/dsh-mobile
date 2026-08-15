#!/bin/bash
# download-assets.sh - 在 GitHub Actions 构建时下载 proot 和 rootfs
set -e

ASSETS_DIR="app/src/main/assets"
PROOT_DIR="$ASSETS_DIR/bin"
ROOTFS_DIR="$ASSETS_DIR/rootfs"

mkdir -p "$PROOT_DIR"
mkdir -p "$ROOTFS_DIR"

echo "=== 下载 proot ==="
wget -q "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static" -O "$PROOT_DIR/proot-aarch64"
chmod +x "$PROOT_DIR/proot-aarch64"
echo "proot 下载完成: $(ls -lh $PROOT_DIR/proot-aarch64)"

echo "=== 下载 Ubuntu rootfs ==="
wget -q "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz" -O "$ROOTFS_DIR/ubuntu-rootfs.tar.gz"
echo "rootfs 下载完成: $(ls -lh $ROOTFS_DIR/ubuntu-rootfs.tar.gz)"

echo "=== 完成 ==="
ls -lh "$PROOT_DIR/"
ls -lh "$ROOTFS_DIR/"
