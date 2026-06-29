#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# 离线依赖下载脚本
# 在安装前先跑一次，会下载所有外部依赖到 deploy/deps/
# 之后 install.sh 使用本地依赖，无需联网
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPS_DIR="${SCRIPT_DIR}/deps"
mkdir -p "$DEPS_DIR"

# 颜色
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERR]${NC} $*"; }

download() {
    local url="$1" out="$2" desc="$3"
    if [ -f "$out" ] && [ -s "$out" ]; then
        local size=$(ls -lh "$out" | awk '{print $5}')
        ok "$desc 已存在 ($size)，跳过"
        return 0
    fi
    info "下载 $desc ..."
    curl -L --connect-timeout 10 --max-time 300 --progress-bar "$url" -o "$out" || {
        rm -f "$out"
        warn "$desc 直连失败，尝试镜像..."
        return 1
    }
    if [ -s "$out" ]; then
        local size=$(ls -lh "$out" | awk '{print $5}')
        ok "$desc 下载完成 ($size)"
        return 0
    fi
    rm -f "$out"
    return 1
}

download_mirror() {
    local url="$1" mirror="$2" out="$3" desc="$4"
    download "$url" "$out" "$desc" || download "$mirror" "$out" "$desc (镜像)" || {
        err "$desc 所有源下载失败"
        return 1
    }
}

ARCH=$(uname -m)
[ "$ARCH" = "aarch64" ] && JDK_ARCH="aarch64" || JDK_ARCH="x64"
JDK_VER="21.0.3+9"

echo ""
echo "╔══════════════════════════════════════════╗"
echo "║       离线依赖下载工具                   ║"
echo "║       架构: $ARCH                        "
echo "╚══════════════════════════════════════════╝"
echo ""

# ---- JDK21 ----
JDK_FILE="OpenJDK21U-jdk_${JDK_ARCH}_linux_hotspot_21.0.3_9.tar.gz"
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${JDK_VER}/${JDK_FILE}"
JDK_MIRROR="https://mirror.ghproxy.com/https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${JDK_VER}/${JDK_FILE}"
download_mirror "$JDK_URL" "$JDK_MIRROR" "${DEPS_DIR}/${JDK_FILE}" "JDK21 ($ARCH ~200MB)"

# ---- Maven ----
MAVEN_FILE="apache-maven-3.9.9-bin.tar.gz"
download_mirror \
    "https://repo.huaweicloud.com/apache/maven/maven-3/3.9.9/binaries/${MAVEN_FILE}" \
    "https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/${MAVEN_FILE}" \
    "${DEPS_DIR}/${MAVEN_FILE}" "Maven 3.9.9 (~10MB)"

# ---- Mihomo (Clash Meta) ----
[ "$ARCH" = "aarch64" ] && MIHOMO_ARCH="arm64" || MIHOMO_ARCH="amd64"
MIHOMO_FILE="mihomo-linux-${MIHOMO_ARCH}-v1.18.10.gz"
MIHOMO_URL="https://github.com/MetaCubeX/mihomo/releases/download/v1.18.10/mihomo-linux-${MIHOMO_ARCH}-v1.18.10.gz"
MIHOMO_MIRROR="https://mirror.ghproxy.com/https://github.com/MetaCubeX/mihomo/releases/download/v1.18.10/mihomo-linux-${MIHOMO_ARCH}-v1.18.10.gz"
download_mirror "$MIHOMO_URL" "$MIHOMO_MIRROR" "${DEPS_DIR}/mihomo.gz" "Mihomo v1.18.10 ($ARCH ~10MB)"

# ---- GPG Keys ----
if [ ! -f "${DEPS_DIR}/ACCC4CF8.asc" ]; then
    curl -sL "https://www.postgresql.org/media/keys/ACCC4CF8.asc" -o "${DEPS_DIR}/ACCC4CF8.asc" && ok "PostgreSQL GPG key" || warn "PG GPG key 下载失败"
fi
if [ ! -f "${DEPS_DIR}/GPG-KEY-elasticsearch" ]; then
    curl -sL "https://mirrors.huaweicloud.com/elasticstack/GPG-KEY-elasticsearch" -o "${DEPS_DIR}/GPG-KEY-elasticsearch" && ok "Elasticsearch GPG key" || warn "ES GPG key 下载失败"
fi

echo ""
echo "╔══════════════════════════════════════════╗"
echo "║       离线依赖下载完成                    ║"
echo "╚══════════════════════════════════════════╝"
ls -lh "${DEPS_DIR}/"
echo ""
echo "现在可以运行 sudo bash deploy/install.sh 进行离线安装了"
