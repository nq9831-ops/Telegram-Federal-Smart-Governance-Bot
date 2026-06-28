#!/usr/bin/env bash
# ============================================================
# 离线依赖包下载脚本
# 在部署服务器上运行，从国内镜像下载所有依赖
# ============================================================
set -euo pipefail

DEPS_DIR="$(cd "$(dirname "$0")" && pwd)"
ARCH=$(uname -m)

# 架构映射
JDK_ARCH="x64"
ES_ARCH="x86_64"
NODE_ARCH="x64"
if [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then
    JDK_ARCH="aarch64"
    ES_ARCH="arm64"
    NODE_ARCH="arm64"
fi

echo "=== 下载离线依赖包到 $DEPS_DIR ==="
echo "系统架构: $ARCH  →  JDK: ${JDK_ARCH}  ES: ${ES_ARCH}  Node: ${NODE_ARCH}"

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

# JDK 21 — Adoptium（华为云镜像）
download "https://mirrors.huaweicloud.com/adoptium/21/jdk/${JDK_ARCH}/linux/OpenJDK21U-jdk_${JDK_ARCH}_linux_hotspot_21.0.3_9.tar.gz" "OpenJDK21U-jdk_${JDK_ARCH}_linux_hotspot_21.0.3_9.tar.gz"

# Maven 3.9.9
download "https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz" "apache-maven-3.9.9-bin.tar.gz"

# ES 8.15.5（华为云镜像）
download "https://mirrors.huaweicloud.com/elasticsearch/8.15.5/elasticsearch-8.15.5-linux-${ES_ARCH}.tar.gz" "elasticsearch-8.15.5-linux-${ES_ARCH}.tar.gz"

# Node.js 20
download "https://nodejs.org/dist/v20.19.0/node-v20.19.0-linux-${NODE_ARCH}.tar.xz" "node-v20.19.0-linux-${NODE_ARCH}.tar.xz"

echo ""
echo "=== 完成！deps/ 内容 ==="
ls -lh "$DEPS_DIR"
