#!/usr/bin/env bash
# ============================================================
# 离线依赖包下载脚本
# 在部署服务器上运行，从官方源下载所有依赖
# ============================================================
set -euo pipefail

DEPS_DIR="$(cd "$(dirname "$0")" && pwd)"
ARCH=$(uname -m)

echo "=== 下载离线依赖包到 $DEPS_DIR ==="
echo "架构: $ARCH"

download() {
    local url="$1"
    local filename="$2"
    if [ -f "$DEPS_DIR/$filename" ]; then
        echo "  ✅ 已存在: $filename"
    else
        echo "  ⬇️  下载: $filename"
        curl -#L -o "$DEPS_DIR/$filename" "$url" || {
            echo "  ❌ 下载失败"
            return 1
        }
    fi
}

# JDK 21
download "https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz" "jdk-21_linux-x64_bin.tar.gz"

# Maven 3.9.9
download "https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz" "apache-maven-3.9.9-bin.tar.gz"

# ES 8.15
download "https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-8.15.0-linux-x86_64.tar.gz" "elasticsearch-8.15.0-linux-x86_64.tar.gz"

# Node.js 20
if [ "$ARCH" = "x86_64" ] || [ "$ARCH" = "amd64" ]; then
    download "https://nodejs.org/dist/v20.19.0/node-v20.19.0-linux-x64.tar.xz" "node-v20.19.0-linux-x64.tar.xz"
elif [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then
    download "https://nodejs.org/dist/v20.19.0/node-v20.19.0-linux-arm64.tar.xz" "node-v20.19.0-linux-arm64.tar.xz"
fi

echo ""
echo "=== 完成！deps/ 内容 ==="
ls -lh "$DEPS_DIR"
