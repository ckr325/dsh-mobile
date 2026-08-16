#!/bin/bash
# download-assets.sh - 在 GitHub Actions 构建时下载 proot 和 rootfs
set -e

ASSETS_DIR="app/src/main/assets"
PROOT_DIR="$ASSETS_DIR/bin"
ROOTFS_DIR="$ASSETS_DIR/rootfs"

mkdir -p "$PROOT_DIR"
mkdir -p "$ROOTFS_DIR"

echo "=== 下载 proot ==="
for i in 1 2 3; do
    echo "尝试 $i/3..."
    if curl -L --retry 3 --retry-delay 5 -o "$PROOT_DIR/proot-aarch64" \
        "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static"; then
        echo "proot 下载成功: $(ls -lh $PROOT_DIR/proot-aarch64)"
        break
    fi
    echo "下载失败，等待重试..."
    sleep 5
done

chmod +x "$PROOT_DIR/proot-aarch64"

echo ""
echo "=== 下载 Ubuntu rootfs ==="
for i in 1 2 3; do
    echo "尝试 $i/3..."
    if curl -L --retry 3 --retry-delay 5 -o "$ROOTFS_DIR/ubuntu-rootfs.tar.gz" \
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz"; then
        echo "rootfs 下载成功: $(ls -lh $ROOTFS_DIR/ubuntu-rootfs.tar.gz)"
        break
    fi
    echo "下载失败，等待重试..."
    sleep 5
done

echo ""
echo "=== 验证文件 ==="
ls -lh "$PROOT_DIR/"
ls -lh "$ROOTFS_DIR/"

# 验证文件大小
PROOT_SIZE=$(stat -c%s "$PROOT_DIR/proot-aarch64" 2>/dev/null || echo "0")
ROOTFS_SIZE=$(stat -c%s "$ROOTFS_DIR/ubuntu-rootfs.tar.gz" 2>/dev/null || echo "0")

echo "proot 大小: $PROOT_SIZE bytes"
echo "rootfs 大小: $ROOTFS_SIZE bytes"

if [ "$PROOT_SIZE" -lt 100000 ]; then
    echo "ERROR: proot 文件太小，可能下载失败"
    exit 1
fi

if [ "$ROOTFS_SIZE" -lt 10000000 ]; then
    echo "ERROR: rootfs 文件太小，可能下载失败"
    exit 1
fi

echo "=== 所有文件验证通过 ==="
