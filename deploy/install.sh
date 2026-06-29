# 防止 apt 卡死：非交互模式 + 锁等待
export DEBIAN_FRONTEND=noninteractive
# apt 锁等待（最多60秒）
APT_LOCK_WAIT() { sleep 0.5; for i in $(seq 1 60); do ! fuser /var/lib/dpkg/lock-frontend /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock 2>/dev/null && break; sleep 1; done; }

#!/usr/bin/env bash
# ============================================================
# TG联邦智能治理机器人 - 内置Clash Meta代理完整版（修复优化）
# 修复：kcla安装ghproxy加速、代理端口全局变量统一、无硬编码端口
# 执行：sudo bash install.sh
# ============================================================
set -uo pipefail

# 颜色定义
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'
info() { echo -e "${BLUE}[INFO]${NC} $1"; }
ok() { echo -e "${GREEN}[OK]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
err() { echo -e "${RED}[ERR]${NC} $1" >&2; }
input() { echo -e "${CYAN}[INPUT]${NC} $1"; }

# 权限校验
if [ "$EUID" -ne 0 ]; then err "必须使用 sudo / root 执行脚本"; exit 1; fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DEPS_DIR="${SCRIPT_DIR}/deps"

# 全局代理统一变量（仅此处修改端口即可全局生效）

# 自动下载项目代码（GitHub raw → 无需本地 clone）
REPO_URL="https://api.github.com/repos/nq9831-ops/Telegram-Federal-Smart-Governance-Bot/tarball/main"
REPO_URL_MIRROR="https://mirror.ghproxy.com/https://api.github.com/repos/nq9831-ops/Telegram-Federal-Smart-Governance-Bot/tarball/main"
AUTO_DOWNLOAD_DIR="/opt/tg-federal-bot"

# 只有当脚本通过 curl | bash 执行（SCRIPT_DIR==/tmp 或不存在）且项目目录没有 pom.xml 时，才自动下载
if [ ! -f "$PROJECT_DIR/pom.xml" ]; then
    if [ -f "$AUTO_DOWNLOAD_DIR/pom.xml" ]; then
        info "检测到 $AUTO_DOWNLOAD_DIR 已有项目文件，直接使用"
        PROJECT_DIR="$AUTO_DOWNLOAD_DIR"
        SCRIPT_DIR="$PROJECT_DIR/deploy"
    else
        info "未检测到本地项目，从 GitHub 自动下载..."
        mkdir -p "$AUTO_DOWNLOAD_DIR"
        TMP_TGZ="/tmp/tg-federal-bot-$(date +%s).tar.gz"
        rm -f "$TMP_TGZ"
        if curl -sL --connect-timeout 10 --max-time 60 -H "Accept: application/vnd.github+json" \
            "$REPO_URL" -o "$TMP_TGZ" 2>/dev/null && [ -s "$TMP_TGZ" ]; then
            :
        elif curl -sL --connect-timeout 10 --max-time 60 -H "Accept: application/vnd.github+json" \
            "$REPO_URL_MIRROR" -o "$TMP_TGZ" 2>/dev/null && [ -s "$TMP_TGZ" ]; then
            info "使用 ghproxy 国内加速下载"
        else
            err "项目下载失败，请确保网络可达 GitHub API"
            err "或手动执行: git clone git@github.com:nq9831-ops/Telegram-Federal-Smart-Governance-Bot.git"
            exit 1
        fi
        ok "下载完成，解压中..."
        rm -rf "$AUTO_DOWNLOAD_DIR"/*
        # GitHub tarball 解压出来是嵌套目录 xxx-tg-federal-bot-xxx/ 结构
        TMP_EXTRACT="/tmp/tg-federal-bot-extract-$(date +%s)"
        mkdir -p "$TMP_EXTRACT"
        tar -xzf "$TMP_TGZ" -C "$TMP_EXTRACT" 2>/dev/null || {
            err "解压失败，tar.gz 可能损坏"
            rm -f "$TMP_TGZ"; rm -rf "$TMP_EXTRACT"
            exit 1
        }
        # 找到解压的目录
        inner_dir=$(ls -d "$TMP_EXTRACT"/*/ 2>/dev/null | head -1)
        if [ -n "$inner_dir" ]; then
            cp -r "$inner_dir"/* "$AUTO_DOWNLOAD_DIR/" 2>/dev/null || cp -r "$inner_dir"/. "$AUTO_DOWNLOAD_DIR/"
        fi
        rm -f "$TMP_TGZ"; rm -rf "$TMP_EXTRACT"
        PROJECT_DIR="$AUTO_DOWNLOAD_DIR"
        SCRIPT_DIR="$PROJECT_DIR/deploy"
        ok "项目已下载至 $AUTO_DOWNLOAD_DIR"
    fi
fi

# 重新确认
cd "$PROJECT_DIR"
[ ! -f pom.xml ] && { err "项目文件不完整，缺少 pom.xml"; exit 1; }

PROXY_SOCKS_HOST="127.0.0.1"
PROXY_SOCKS_PORT="7890"
PROXY_FULL="socks5h://${PROXY_SOCKS_HOST}:${PROXY_SOCKS_PORT}"

TIMEOUT_DEFAULT=60

read_with_timeout() {
    local prompt="$1"
    local default="${2:-}"
    local timeout="${3:-$TIMEOUT_DEFAULT}"
    if [ -n "$prompt" ]; then
        echo -e "${CYAN}[INPUT]${NC} $prompt"
    fi
    if [[ -t 0 ]]; then
        read -t "$timeout" -r input
        if [ $? -ne 0 ]; then
            if [ -n "$default" ]; then
                echo -e "${YELLOW}[WARN]${NC} Input timeout, using default: $default"
                input="$default"
            else
                echo -e "${RED}[ERR]${NC} Input timeout, this field is required"
                exit 1
            fi
        fi
    else
        if ! read -t 1 -r input 2>/dev/null; then
            if [ -n "$default" ]; then
                echo -e "${YELLOW}[WARN]${NC} Non-interactive mode, using default: $default"
                input="$default"
            else
                echo -e "${RED}[ERR]${NC} Non-interactive mode requires env var"
                exit 1
            fi
        fi
    fi
    echo "$input"
}

# 全局变量预声明
BOT_TOKEN=""
BOT_USERNAME=""
BOT_CREATOR=""
MODERATION_MODE=""
MODERATION_PROVIDER=""
LOCAL_OLLAMA_URL=""
LOCAL_MODEL=""
DEEPSEEK_KEY=""
INSTALL_OLLAMA=""
WEBHOOK_URL=""
PROXY_ENABLED="true"
DB_PASSWORD=""
INSTALL_ES=""
WEBHOOK_SECRET=""
CODENAME=""
CLASH_SUBSCRIBE=""

# ==============================================
# 工具函数：获取系统代号CODENAME（修复lsb_release缺失）
# ==============================================
get_codename() {
 if command -v lsb_release &>/dev/null; then
 CODENAME=$(lsb_release -cs)
 return
 fi
 if [[ "$OS_ID" == "ubuntu" ]]; then
 case "${OS_VER%%.*}" in
 20) CODENAME="focal" ;;
 22) CODENAME="jammy" ;;
 24) CODENAME="noble" ;;
 *) CODENAME="jammy" ;;
 esac
 elif [[ "$OS_ID" == "debian" ]]; then
 case "${OS_VER%%.*}" in
 11) CODENAME="bullseye" ;;
 12) CODENAME="bookworm" ;;
 *) CODENAME="bookworm" ;;
 esac
 else
 CODENAME="jammy"
 fi
}

# ==============================================
# 系统检测 & 替换阿里云源
# ==============================================
detect_os() {
 if [ -f /etc/os-release ]; then
 . /etc/os-release
 OS_ID="$ID"; OS_LIKE="$ID_LIKE"; OS_VER="${VERSION_ID:-}"
 elif [ -f /etc/debian_version ]; then
 OS_ID="debian"
 OS_VER=$(cat /etc/debian_version | cut -d'.' -f1)
 elif [ -f /etc/redhat-release ]; then
 OS_ID="rhel"
 OS_VER=$(grep -oE '[0-9]+\.[0-9]+' /etc/redhat-release | head -1)
 else
 OS_ID="unknown"
 fi
 info "检测系统: ${OS_ID} ${OS_VER}"
 get_codename
 info "系统代号自动识别: CODENAME=${CODENAME}"

 case "$OS_ID" in
 ubuntu|debian|kali|linuxmint)
 pkg_update() { APT_LOCK_WAIT && apt-get update; }
 pkg_install() { APT_LOCK_WAIT && apt-get install -y; }
 if [ -f /etc/apt/sources.list ] && ! grep -q "mirrors.aliyun.com" /etc/apt/sources.list \
        && [ "$(uname -m)" != "aarch64" ]; then
 cp /etc/apt/sources.list /etc/apt/sources.list.bak.$(date +%Y%m%d) 2>/dev/null || true
 if [ "$OS_ID" = "ubuntu" ]; then
 cat > /etc/apt/sources.list <<EOF
deb https://mirrors.aliyun.com/ubuntu/ $CODENAME main restricted universe multiverse
deb https://mirrors.aliyun.com/ubuntu/ $CODENAME-security main restricted universe multiverse
deb https://mirrors.aliyun.com/ubuntu/ $CODENAME-updates main restricted universe multiverse
deb https://mirrors.aliyun.com/ubuntu/ $CODENAME-backports main restricted universe multiverse
EOF
 elif [ "$OS_ID" = "debian" ]; then
 cat > /etc/apt/sources.list <<EOF
deb https://mirrors.aliyun.com/debian/ $CODENAME main contrib non-free
deb https://mirrors.aliyun.com/debian/ $CODENAME-updates main contrib non-free
deb https://mirrors.aliyun.com/debian-security ${CODENAME}-security main contrib non-free
EOF
 fi
 ok "apt 源切换阿里云完成"
 fi
 ;;
 fedora|rhel|centos|rocky|almalinux)
 pkg_update() { dnf makecache -q 2>/dev/null || yum makecache -q 2>/dev/null || true; }
 pkg_install() { dnf install -y -q 2>/dev/null || yum install -y -q 2>/dev/null || true; }
 major_ver="${OS_VER%%.*}"
 if [[ "$OS_ID" == "centos" && -f /etc/yum.repos.d/CentOS-Base.repo ]] && ! grep -q "mirrors.aliyun.com\|vault.centos.org" /etc/yum.repos.d/CentOS-Base.repo; then
 if [ "$major_ver" = "7" ]; then
 info "CentOS7 EOL，切换Vault归档源"
 sed -i 's|mirror.centos.org|vault.centos.org|g' /etc/yum.repos.d/CentOS-Base.repo
 sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-Base.repo
 sed -i 's|mirrorlist=|#mirrorlist=|g' /etc/yum.repos.d/CentOS-Base.repo
 else
 curl -sL -o /etc/yum.repos.d/CentOS-Base.repo "https://mirrors.aliyun.com/repo/Centos-${major_ver}.repo" 2>/dev/null || true
 fi
 ok "yum 源切换国内镜像完成"
 fi
 ;;
 arch|manjaro)
 pkg_update() { pacman -Sy --noconfirm; }
 pkg_install() { pacman -S --noconfirm; }
 ;;
 *)
 err "不支持操作系统: $OS_ID"
 exit 1
 ;;
 esac
 pkg_update 2>/dev/null || warn "源更新警告可忽略"
}

# ==============================================
# 集成模块：自动安装 & 配置 Clash Meta (kcla)
# 修复：ghproxy 国内加速兜底，解决cdn打不开问题
# ==============================================
install_clash_meta() {
 info "===== 开始部署本地 Mihomo 代理（${PROXY_FULL}） ====="

 MIHOMO_BIN="/usr/local/bin/mihomo"
 MIHOMO_CFG="/etc/mihomo"
 MIHOMO_VER="1.18.10"
 ARCH=$(uname -m)

 if command -v mihomo &>/dev/null || [ -f "$MIHOMO_BIN" ]; then
 ok "检测到已安装 mihomo"
 elif command -v kc &>/dev/null; then
 ok "检测到已安装 kcla（旧版），保持现有安装"
 else
 info "安装 Mihomo v${MIHOMO_VER} (原 Clash Meta，国内镜像加速)"

 # 架构映射
 case "$ARCH" in
 x86_64) ARCH="amd64" ;;
 aarch64|arm64) ARCH="arm64" ;;
 armv7l|armv8l) ARCH="armv7" ;;
 *) warn "不支持的架构: $ARCH，尝试 amd64"; ARCH="amd64" ;;
 esac

 local TGZ="mihomo-linux-${ARCH}-v${MIHOMO_VER}.gz"

 rm -f "/tmp/${TGZ}"
 # 本地 deps 优先
 if [ -f "${DEPS_DIR}/mihomo.gz" ]; then
 info " -> 使用本地离线包: ${DEPS_DIR}/mihomo.gz"
 cp "${DEPS_DIR}/mihomo.gz" "/tmp/${TGZ}"
 else
 local URL="https://mirror.ghproxy.com/https://github.com/MetaCubeX/mihomo/releases/download/v${MIHOMO_VER}/mihomo-linux-${ARCH}-v${MIHOMO_VER}.gz"
 local URL_FALLBACK="https://github.com/MetaCubeX/mihomo/releases/download/v${MIHOMO_VER}/mihomo-linux-${ARCH}-v${MIHOMO_VER}.gz"
 if ! curl -sL --connect-timeout 10 --max-time 60 "$URL" -o "/tmp/${TGZ}" 2>/dev/null; then
 info "ghproxy 下载失败，切换 GitHub 直连..."
 curl -sL --connect-timeout 10 --max-time 60 "$URL_FALLBACK" -o "/tmp/${TGZ}" 2>/dev/null || {
 err "Mihomo 下载失败，请手动安装: https://github.com/MetaCubeX/mihomo/releases"
 exit 1
 }
 fi
 fi

 if [ -s "/tmp/${TGZ}" ]; then
 gunzip -c "/tmp/${TGZ}" > "$MIHOMO_BIN" 2>/dev/null || {
 # 如果 gz 不是 gzip 格式，可能是 tar.gz，尝试解压
 rm -f "$MIHOMO_BIN"
 cd /tmp && tar -xzf "$TGZ" 2>/dev/null || true
 find /tmp -name "mihomo*" -type f ! -name "*.gz" ! -name "*.tar" -exec cp {} "$MIHOMO_BIN" \; 2>/dev/null || true
 }
 chmod +x "$MIHOMO_BIN"
 ok "Mihomo 二进制安装完成"
 rm -f "/tmp/${TGZ}"
 fi
 fi

 # 如果 mihomo 不可用但旧版 kcla 在，用旧版逻辑
 if command -v kc &>/dev/null; then
 KCLA_MODE=true
 info "使用旧版 kcla 管理代理"
 else
 KCLA_MODE=false
 if [ ! -f "$MIHOMO_BIN" ]; then
 err "Mihomo 二进制未安装成功，请检查网络后重试"
 exit 1
 fi
 fi

 CLASH_SUBSCRIBE="${CLASH_SUBSCRIBE:-}"
    if [ -z "$CLASH_SUBSCRIBE" ]; then
        input "请输入Clash/Mihomo订阅链接（必填）："
        input=$(read_with_timeout "" "")
        CLASH_SUBSCRIBE="$input"
        while [ -z "$CLASH_SUBSCRIBE" ]; do
            warn "订阅链接不能为空"
            input=$(read_with_timeout "请输入Clash/Mihomo订阅链接：" "")
            CLASH_SUBSCRIBE="$input"
        done
    fi

 if [ "$KCLA_MODE" = true ]; then
 # 旧版 kcla 逻辑
 kc sub add "$CLASH_SUBSCRIBE"
 kc sub update
 kc enable
 kc start
 sleep 3
 ok "kcla 代理已启动，socks5 监听 ${PROXY_FULL}"
 else
 # mihomo 配置
 mkdir -p "$MIHOMO_CFG"
 # 从订阅链接下载配置
 info "下载订阅配置..."
 if curl -sL --connect-timeout 10 --max-time 60 "$CLASH_SUBSCRIBE" -o "${MIHOMO_CFG}/config.yaml" 2>/dev/null; then
 :
 elif curl -sL --connect-timeout 10 --max-time 60 --user-agent "clash" "$CLASH_SUBSCRIBE" -o "${MIHOMO_CFG}/config.yaml" 2>/dev/null; then
 :
 else
 err "订阅配置下载失败，请检查链接有效性"
 exit 1
 fi

 # 写入 systemd 服务
 cat > /etc/systemd/system/mihomo.service << 'EOF'
[Unit]
Description=Mihomo (Clash Meta) Proxy
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/mihomo -d /etc/mihomo
Restart=on-failure
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
 systemctl daemon-reload
 systemctl enable --now mihomo
 sleep 3
 ok "Mihomo 已启动，socks5 监听 ${PROXY_FULL}"
 fi

 # 预检测代理连通TG API
 info "正在校验代理是否可访问 Telegram API..."
 local test_url="https://api.telegram.org/botdummy/getMe"
 if curl -x "${PROXY_FULL}" --connect-timeout 8 -s "$test_url" | grep -q "ok"; then
 ok "代理连通性校验通过，可正常访问TG接口"
 else
 err "代理无法访问TG，请检查订阅节点有效性，脚本终止"
 exit 1
 fi
}

# ==============================================
# Maven 阿里云镜像
# ==============================================
setup_maven_mirror() {
 mkdir -p /root/.m2
 cat > /root/.m2/settings.xml <<'MAVEN'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
 xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0
 https://maven.apache.org/xsd/settings-1.2.0.xsd">
 <mirrors>
 <mirror>
 <id>aliyun-public</id>
 <name>Aliyun Maven Public Mirror</name>
 <url>https://maven.aliyun.com/repository/public</url>
 <mirrorOf>*</mirrorOf>
 </mirror>
 </mirrors>
 <interactiveMode>false</interactiveMode>
 <offline>false</offline>
</settings>
MAVEN
 chmod 644 /root/.m2/settings.xml
 ok "Maven 阿里云镜像配置完成"
}

# ==============================================
# 交互式配置（强制代理、PG密码强校验）
# ==============================================
interactive_config() {
 echo ""
 echo "╔════════════════════════════════════════════════════╗"
 echo "║ TG联邦智能治理机器人 - 部署配置向导 ║"
 echo "║ 代理状态: $([ "$PROXY_ENABLED" = "true" ] && echo "启用\${PROXY_FULL}" || echo "直连(无需代理)") ║"
 echo "╚════════════════════════════════════════════════════╝"
 echo ""

 # Bot Token
 BOT_TOKEN="${BOT_TOKEN:-}"
    if [ -z "$BOT_TOKEN" ]; then
        input "Telegram Bot Token（@BotFather 获取）："
        BOT_TOKEN=$(read_with_timeout "" "")
        while [ -z "$BOT_TOKEN" ]; do
            input "Telegram Bot Token："
            BOT_TOKEN=$(read_with_timeout "" "")
        done
    fi
 ok "Bot Token: ${BOT_TOKEN:0:10}******"

 # Bot用户名
 input "Bot 用户名（不带@，默认 YourBot_bot）："
 BOT_USERNAME="${BOT_USERNAME:-}"
        if [ -z "$BOT_USERNAME" ]; then
            input "Bot用户名（选填）："; input=$(read_with_timeout "" ""); BOT_USERNAME="$input"
        fi
 BOT_USERNAME="${BOT_USERNAME:-YourBot_bot}"
 ok "Bot 用户名: ${BOT_USERNAME}"

 # 管理员ID
 input "超级管理员UserID（多ID逗号分隔，默认 5006320370）："
 BOT_CREATOR="${BOT_CREATOR:-}"
        if [ -z "$BOT_CREATOR" ]; then
            input "管理员用户ID（必填）："; input=$(read_with_timeout "" ""); BOT_CREATOR="$input"
            while [ -z "$BOT_CREATOR" ]; do
                input "管理员用户ID："; input=$(read_with_timeout "" ""); BOT_CREATOR="$input"
            done
        fi
 BOT_CREATOR="${BOT_CREATOR:-5006320370}"
 ok "超级管理员: ${BOT_CREATOR}"

 # 审核模式
 echo ""
 input "内容审核模式 — 1)云端DeepSeek 2)本地Ollama [1/2]（默认1）："
 MODERATION_MODE="${MODERATION_MODE:-auto}"
        input "审核模式 (auto/manual)："; input=$(read_with_timeout "" "auto"); MODERATION_MODE="$input"
 MODERATION_MODE="${MODERATION_MODE:-1}"
 if [ "$MODERATION_MODE" = "2" ]; then
 MODERATION_PROVIDER="local"
 input "Ollama地址（默认 http://localhost:11434）："
 LOCAL_OLLAMA_URL="${LOCAL_OLLAMA_URL:-}"
        input "Ollama地址（选填）："; input=$(read_with_timeout "" ""); LOCAL_OLLAMA_URL="$input"
 LOCAL_OLLAMA_URL="${LOCAL_OLLAMA_URL:-http://localhost:11434}"
 input "本地模型（默认 qwen2.5:7b）："
 LOCAL_MODEL="${LOCAL_MODEL:-qwen2.5:7b}"
        input "模型名："; input=$(read_with_timeout "" "qwen2.5:7b"); LOCAL_MODEL="$input"
 LOCAL_MODEL="${LOCAL_MODEL:-qwen2.5:7b}"
 DEEPSEEK_KEY=""
 ok "本地审核: ${LOCAL_MODEL}"
 input "自动安装Ollama并拉取模型？[y/N]"
 INSTALL_OLLAMA="${INSTALL_OLLAMA:-yes}"
        input "安装Ollama？(yes/no)："; input=$(read_with_timeout "" "yes"); INSTALL_OLLAMA="$input"
 else
 MODERATION_PROVIDER="cloud"
 LOCAL_OLLAMA_URL="http://localhost:11434"
 LOCAL_MODEL="qwen2.5:7b"
 input "DeepSeek API Key（留空则降级规则审核）："
 DEEPSEEK_KEY="${DEEPSEEK_KEY:-}"
        input "DeepSeek Key（选填）："; input=$(read_with_timeout "" ""); DEEPSEEK_KEY="$input"
 INSTALL_OLLAMA="n"
 [ -z "$DEEPSEEK_KEY" ] && warn "未配置DeepSeek，仅本地关键词审核" || ok "DeepSeek密钥: ${DEEPSEEK_KEY:0:8}******"
 fi

 # Webhook
 input "Webhook公网域名（无则回车使用长轮询）："
 WEBHOOK_URL="${WEBHOOK_URL:-}"
        input "Webhook URL（选填）："; input=$(read_with_timeout "" ""); WEBHOOK_URL="$input"
 WEBHOOK_URL="${WEBHOOK_URL:-}"
 [ -z "$WEBHOOK_URL" ] && warn "未配置Webhook，使用Long Polling接收消息"

 # PostgreSQL 密码强校验：禁止空/默认postgres
 DB_PASSWORD="${DB_PASSWORD:-}"
    local auto_pwd=$(openssl rand -base64 12 2>/dev/null || echo "AutoGen@2024!")
    [ -z "$DB_PASSWORD" ] && DB_PASSWORD="$auto_pwd"
    while [[ ${#DB_PASSWORD} -lt 8 || "$DB_PASSWORD" == "postgres" ]]; do
        input "PostgreSQL密码（至少8位，不能是postgres）："
        DB_PASSWORD=$(read_with_timeout "" "$auto_pwd")
        if [[ ${#DB_PASSWORD} -ge 8 && "$DB_PASSWORD" != "postgres" ]]; then
            break
        fi
        warn "密码不合规，使用自动生成: $auto_pwd"
        DB_PASSWORD="$auto_pwd"
        break
    done

 # Elasticsearch
 input "安装Elasticsearch评分引擎？[Y/n]"
 INSTALL_ES="${INSTALL_ES:-yes}"
        input "安装ES？(yes/no)："; input=$(read_with_timeout "" "yes"); INSTALL_ES="$input"

 # 配置确认
 echo ""
 echo "╔════════════════════════════════════════════════════╗"
 echo "║ 部署配置总览确认 ║"
 echo "╠════════════════════════════════════════════════════╣"
 echo "║ Token: ${BOT_TOKEN:0:15}******"
 echo "║ Bot账号: ${BOT_USERNAME}"
 echo "║ 管理员ID: ${BOT_CREATOR}"
 echo "║ 审核引擎: $([ "$MODERATION_PROVIDER" = "local" ] && echo "本地模型 ${LOCAL_MODEL}" || echo "云端DeepSeek")"
 echo "║ Webhook: $([ -n "$WEBHOOK_URL" ] && echo "$WEBHOOK_URL" || echo "长轮询")"
 if [ "$PROXY_ENABLED" = "true" ]; then
    echo "║ TG代理: 启用 ${PROXY_FULL}（内置Clash Meta）"
    else
    echo "║ TG代理: 直连（无需代理）"
    fi
 echo "║ Elasticsearch: $([[ "$INSTALL_ES" =~ ^[nN] ]] && echo "不安装" || echo "安装")"
 echo "╚════════════════════════════════════════════════════╝"
 echo ""
 input "确认开始部署？[Y/n]"; read -r CONFIRM
 [[ "$CONFIRM" =~ ^[nN] ]] && { warn "取消部署，脚本退出"; exit 0; }
}

# ==============================================
# 写入配置文件（全局变量统一代理端口，无硬编码）
# ==============================================
write_config() {
 info "生成程序配置 /etc/tg-federal-bot/"
 mkdir -p /etc/tg-federal-bot

 # 敏感配置
 cat > /etc/tg-federal-bot/application.properties <<PROPS
# 敏感凭据，权限600仅root可读
spring.datasource.password=${DB_PASSWORD}
deepseek.api-key=${DEEPSEEK_KEY}
bot.token=${BOT_TOKEN}
bot.creator=${BOT_CREATOR}
bot.webhook-url=${WEBHOOK_URL}
bot.webhook-secret=${WEBHOOK_SECRET}
webhook.allowed-ips=91.108.4.0/22,91.108.8.0/22,91.108.56.0/22,149.154.160.0/20
webhook.rate-limit-per-second=50
# 代理总开关+全局统一地址端口
bot.proxy.enabled=${PROXY_ENABLED}
bot.proxy.socks5-host=${PROXY_SOCKS_HOST}
bot.proxy.socks5-port=${PROXY_SOCKS_PORT}
PROPS

 # 公共业务配置
 cat > /etc/tg-federal-bot/application-public.properties <<PROPS
spring.application.name=tg-federal-bot
server.port=8080
spring.datasource.url=jdbc:postgresql://localhost:5432/tg_federal_bot
spring.datasource.username=postgres
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=10000
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.baseline-on-migrate=true
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.timeout=3000
spring.cache.type=redis
spring.cache.redis.time-to-live=300s
spring.cache.redis.cache-null-values=false
elasticsearch.uris=http://localhost:9900
elasticsearch.rating-index=rating_v2
moderation.provider=${MODERATION_PROVIDER}
moderation.local.api-url=${LOCAL_OLLAMA_URL}/v1/chat/completions
moderation.local.model=${LOCAL_MODEL}
deepseek.api-url=https://api.deepseek.com/v1/chat/completions
deepseek.model=deepseek-chat
deepseek.timeout-ms=10000
deepseek.max-retries=3
deepseek.fallback-engine=true
captcha.required-for-new-members=true
captcha.arithmetic-enabled=true
captcha.failed-attempt-limit=3
credit.initial=100
credit.daily-auto-increment=1
credit.max-score=100
credit.global-mute-threshold=50
credit.privilege-freeze-threshold=60
deepseek.auto-execute-threshold=0.85
deepseek.human-review-threshold=0.60
system.cold-start-days=7
system.cold-start=true
storage.message-retention-days=90
storage.audit-log-retention-days=180
rate-limit.ip.max-per-second=50
rate-limit.ip.ban-seconds=3600
rate-limit.user.max-per-minute=10
rate-limit.sensitive.max-per-minute=30
feature.deepseek-realtime=true
feature.deepseek-profile=true
feature.death-penalty-auto=true
feature.porn-penalty=true
feature.gambling-penalty=true
feature.ad-penalty=true
feature.high-frequency-detection=true
feature.fingerprint-dedup=true
feature.device-fingerprint=true
feature.report-anti-cheat=true
feature.group-label-system=true
feature.label-audit=true
feature.private-query=true
feature.penalty-notification=true
feature.certified-advertiser=true
feature.invite-reward=true
feature.ranking=true
feature.credit-auto-reward=true
feature.review-system=true
feature.broadcast=true
feature.ip-rate-limit=true
feature.captcha=true
logging.level.com.tgf.bot=INFO
logging.level.org.springframework=WARN
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=never
management.endpoints.web.base-path=/actuator
PROPS

 chmod 600 /etc/tg-federal-bot/application.properties
 chmod 644 /etc/tg-federal-bot/application-public.properties
 ok "配置写入完成，敏感文件权限加固"
}

# ==============================================
# JDK21 多镜像兼容安装
# ==============================================
install_java() {
 if command -v java &>/dev/null; then
 jver=$(java -version 2>&1 | head -1 | grep -oP '"\K\d+' || echo "0")
 if [[ "$jver" =~ ^[0-9]+$ && "$jver" -ge 21 ]]; then
 ok "已有Java $jver，跳过安装"
 return
 fi
 fi
 info "安装 OpenJDK21"
 arch=$(uname -m)
 [[ "$arch" == "aarch64" ]] && arch="aarch64" || arch="x64"
 local install_ok=false
 case "$OS_ID" in
 ubuntu|debian)
 if pkg_install openjdk-21-jdk-headless; then install_ok=true; fi
 [[ "$install_ok" == false ]] && pkg_install openjdk-17-jdk-headless && install_ok=true
 ;;
 centos|rhel|rocky|almalinux|fedora)
 if pkg_install java-21-openjdk-headless; then install_ok=true; fi
 [[ "$install_ok" == false ]] && pkg_install java-17-openjdk-headless && install_ok=true
 ;;
 arch)
 pkg_install jdk21-openjdk && install_ok=true
 ;;
 esac
 if [ "$install_ok" = true ] && command -v java &>/dev/null; then
 jver=$(java -version 2>&1 | head -1 | grep -oP '"\K\d+' || echo "0")
 if [[ "$jver" =~ ^[0-9]+$ && "$jver" -ge 21 ]]; then
 ok "JDK系统包安装完成"
 return
 fi
 fi
 # 系统包取回但版本不对或未安装 — 强制安装
 if [ "$install_ok" = false ] || ! java -version &>/dev/null; then
 info " -> APT 安装未取到 JDK21，强制安装 postgresql 依赖包..."
 pkg_install default-jdk-headless && install_ok=true
 fi
 if [ "$install_ok" = true ] && command -v java &>/dev/null; then
 jver=$(java -version 2>&1 | head -1 | grep -oP '"\K\d+' || echo "0")
 if [[ "$jver" =~ ^[0-9]+$ && "$jver" -ge 17 ]]; then
 ok "JDK$jver 已就绪（最低要求17，recommend 21）"
 return
 fi
 fi
 # 本地 deps 优先
 local jdk_file="OpenJDK21U-jdk_${arch}_linux_hotspot_21.0.3_9.tar.gz"
 if [ -f "${DEPS_DIR}/${jdk_file}" ]; then
 info " -> 使用本地离线包: ${DEPS_DIR}/${jdk_file}"
 mkdir -p /opt
 tar -xzf "${DEPS_DIR}/${jdk_file}" -C /opt/
 else
 # 网络下载
 local jdk_url_arm="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.3%2B9/OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.3_9.tar.gz"
 local jdk_url_x64="https://mirrors.huaweicloud.com/adoptium/21/jdk/${arch}/linux/OpenJDK21U-jdk_${arch}_linux_hotspot_21.0.3_9.tar.gz"
 local jdk_url="${jdk_url_x64}"
 [ "$arch" = "aarch64" ] && jdk_url="${jdk_url_arm}"
 rm -f /tmp/jdk21.tar.gz
 info " -> 下载 JDK21 二进制（约 200MB）..."
 curl -sL --connect-timeout 10 --max-time 300 --progress-bar "$jdk_url" -o /tmp/jdk21.tar.gz || {
 warn "直连失败，尝试镜像..."
 curl -sL --connect-timeout 10 --max-time 300 --progress-bar "https://mirror.ghproxy.com/https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.3%2B9/${jdk_file}" -o /tmp/jdk21.tar.gz
 }
 if [ -s /tmp/jdk21.tar.gz ]; then
 tar -xzf /tmp/jdk21.tar.gz -C /opt/
 fi
 fi
 jdir=$(ls -d /opt/jdk-* /opt/OpenJDK* /opt/jdk21* /opt/Open*JDK* 2>/dev/null | head -1)
 if [ -n "$jdir" ]; then
 update-alternatives --install /usr/bin/java java "$jdir/bin/java" 2100 2>/dev/null || true
 update-alternatives --install /usr/bin/javac javac "$jdir/bin/javac" 2100 2>/dev/null || true
 echo "export JAVA_HOME=$jdir" > /etc/profile.d/jdk21.sh
 chmod 644 /etc/profile.d/jdk21.sh
 ok "JDK21 ($jdir) 部署完成"
 return
 fi
 warn "JDK自动安装失败，请手动部署OpenJDK21"
}

# ==============================================
# Maven 3.9.9 安装
# ==============================================
install_maven() {
 if command -v mvn &>/dev/null; then
 local mvn_ver=$(mvn --version 2>&1 | head -1 | grep -oP '\d+\.\d+\.\d+' || echo "0")
 ok "已存在Maven $mvn_ver，配置镜像"
 setup_maven_mirror
 return
 fi
 info "安装 Maven3.9.9"
 local mvn_pkg="/tmp/maven.tar.gz"
 rm -f "$mvn_pkg"
 # 本地 deps 优先
 if [ -f "${DEPS_DIR}/apache-maven-3.9.9-bin.tar.gz" ]; then
 info " -> 使用本地离线包: ${DEPS_DIR}/apache-maven-3.9.9-bin.tar.gz"
 cp "${DEPS_DIR}/apache-maven-3.9.9-bin.tar.gz" "$mvn_pkg"
 else
 curl -sL --connect-timeout 10 --max-time 120 "https://repo.huaweicloud.com/apache/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz" -o "$mvn_pkg" || \
 curl -sL --connect-timeout 10 --max-time 120 "https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz" -o "$mvn_pkg"
 fi
 if [ -f "$mvn_pkg" ] && [ -s "$mvn_pkg" ]; then
 tar -xzf "$mvn_pkg" -C /opt/
 ln -sf /opt/apache-maven-3.9.9/bin/mvn /usr/local/bin/mvn
 if mvn --version &>/dev/null; then
 ok "Maven3.9.9 安装成功"
 setup_maven_mirror
 return
 fi
 fi
 warn "二进制Maven下载失败，使用系统包"
 pkg_install maven 2>/dev/null || true
 setup_maven_mirror
}

# ==============================================
# PostgreSQL 16
# ==============================================
install_postgres() {
 if command -v psql &>/dev/null; then
 ok "PostgreSQL已存在，重启服务"
 systemctl enable --now postgresql 2>/dev/null || systemctl enable --now postgresql-16 2>/dev/null || true
 sleep 1
 else
 info "正在安装 PostgreSQL16..."
 local CODENAME=$(lsb_release -cs 2>/dev/null || "jammy")
 local arch_pg=$(uname -m)
 case "$OS_ID" in
 ubuntu|debian)
 if [ "$arch_pg" != "aarch64" ]; then
 info " -> 添加 PostgreSQL 官方源..."
 curl -sL "https://www.postgresql.org/media/keys/ACCC4CF8.asc" -o /tmp/pgdg.gpg 2>/dev/null
 if [ -s /tmp/pgdg.gpg ]; then
 gpg --dearmor -o /usr/share/keyrings/pgdg.gpg /tmp/pgdg.gpg
 echo "deb [signed-by=/usr/share/keyrings/pgdg.gpg] https://mirrors.aliyun.com/postgresql/apt/ ${CODENAME}-pgdg main" > /etc/apt/sources.list.d/pgdg.list
 pkg_update
 info " -> 安装 postgresql-16 (约 50MB)..."
 pkg_install postgresql-16 postgresql-client-16
 else
 info " -> GPG 导入失败，使用系统版本..."
 pkg_install postgresql postgresql-client
 fi
 else
 info " -> ARM64 架构，使用系统自带 postgresql"
 pkg_install postgresql postgresql-client
 fi
 ;;
 centos|rhel|rocky|almalinux|fedora)
 local el_ver="${OS_VER%%.*}"
 info " -> 添加 PostgreSQL YUM 源..."
 curl -sL "https://download.postgresql.org/pub/repos/yum/reporpms/EL-${el_ver}-$(uname -m)/pgdg-redhat-repo-latest.noarch.rpm" -o /tmp/pgdg.rpm 2>/dev/null || true
 [ -f /tmp/pgdg.rpm ] && pkg_install /tmp/pgdg.rpm
 pkg_install postgresql16-server postgresql16-contrib
 /usr/pgsql-16/bin/postgresql-16-setup initdb 2>/dev/null || postgresql-setup --initdb 2>/dev/null || true
 ;;
 arch)
 pkg_install postgresql
 ;;
 esac
 info " -> 启动 PostgreSQL 服务..."
 systemctl daemon-reload
 systemctl enable --now postgresql 2>/dev/null || systemctl enable --now postgresql-16 2>/dev/null || true
 sleep 2
 fi
 info " -> 设置密码和创建数据库..."
 su - postgres -c "psql -c \"ALTER USER postgres PASSWORD '${DB_PASSWORD}'\"" 2>/dev/null || true
 su - postgres -c "createdb tg_federal_bot" 2>/dev/null || info "数据库已存在"
 if [[ "$OS_ID" =~ centos|rhel|rocky ]]; then
 local pg_hba=$(find /var/lib/pgsql -name pg_hba.conf | head -1)
 if [ -n "$pg_hba" ]; then
 sed -i 's/peer/md5/g; s/ident/md5/g' "$pg_hba"
 systemctl restart postgresql 2>/dev/null || true
 fi
 fi
 ok "PostgreSQL 初始化完成"
}

# ==============================================
# Redis
# ==============================================
install_redis() {
 if command -v redis-server &>/dev/null; then
 ok "Redis已安装，重启服务"
 systemctl enable --now redis-server 2>/dev/null || systemctl enable --now redis 2>/dev/null || true
 return
 fi
 info "正在安装 Redis..."
 if pkg_install redis-server; then
 ok "Redis-server 安装成功"
 elif pkg_install redis; then
 ok "Redis 安装成功 (备用包名)"
 else
 warn "Redis安装失败，手动部署"
 return
 fi
 info " -> 启动 Redis 服务..."
 systemctl daemon-reload
 systemctl enable --now redis-server 2>/dev/null || systemctl enable --now redis 2>/dev/null || true
 ok "Redis 启动完成"
 redis-cli ping 2>/dev/null && info "Redis 响应: PONG"
}

# ==============================================
# Elasticsearch 配置
# ==============================================
configure_es() {
 local es_conf="/etc/elasticsearch/elasticsearch.yml"
 [ ! -f "$es_conf" ] && return
 sed -i 's/#http.port: 9200/http.port: 9900/' "$es_conf"
 sed -i 's/http.port: [0-9]*/http.port: 9900/' "$es_conf"
 sed -i 's/#network.host:.*/network.host: 0.0.0.0/' "$es_conf"
 grep -q "discovery.type: single-node" "$es_conf" || echo "discovery.type: single-node" >> "$es_conf"
}
install_es() {
 if [ "$(uname -m)" = "aarch64" ]; then
 warn "ARM64 架构不支持 Elasticsearch 官方包，跳过安装"
 warn "如需ES功能，请手动部署: docker run -d --name es -p 9900:9200 elasticsearch:8.17.0"
 return
 fi
 if command -v elasticsearch &>/dev/null; then
 ok "ES已存在，更新配置重启"
 configure_es
 systemctl restart elasticsearch
 return
 fi
 info "安装 Elasticsearch8.x 华为云镜像"
 local arch=$(uname -m)
 ES_ARCH=$([ "$arch" = "x86_64" ] && echo "amd64" || echo "$arch")
 case "$OS_ID" in
 ubuntu|debian)
 curl -sL "https://mirrors.huaweicloud.com/elasticstack/GPG-KEY-elasticsearch" -o /tmp/es.gpg 2>/dev/null
 if [ -s /tmp/es.gpg ]; then
 gpg --dearmor -o /usr/share/keyrings/elasticsearch-keyring.gpg /tmp/es.gpg
 echo "deb [signed-by=/usr/share/keyrings/elasticsearch-keyring.gpg] https://mirrors.huaweicloud.com/elasticstack/8.x/apt stable main" > /etc/apt/sources.list.d/elastic-8.x.list
 pkg_update
 pkg_install elasticsearch
 fi
 ;;
 centos|rhel|fedora|rocky)
 cat > /etc/yum.repos.d/elastic.repo <<'REPO'
[elastic-8.x]
name=Elasticsearch 8 Mirror
baseurl=https://mirrors.huaweicloud.com/elasticstack/8.x/yum
gpgcheck=0
enabled=1
REPO
 pkg_install elasticsearch
 ;;
 arch)
 warn "Arch建议Docker部署ES，跳过自动安装"
 return
 ;;
 esac
 configure_es
 systemctl daemon-reload
 systemctl enable --now elasticsearch
 ok "ES部署完成，端口9900"
}

# ==============================================
# 基础工具 + pip阿里云源
# ==============================================
install_tools() {
 info "安装系统基础依赖"
 local tool_list="wget curl gnupg ca-certificates lsb-release unzip zip git"
 for tool in $tool_list; do
 echo -n "  -> 检查 $tool ... "
 if command -v $tool &>/dev/null; then
 echo "已安装"
 else
 echo "安装中..."
 pkg_install $tool || warn "$tool 安装失败"
 fi
 done
 mkdir -p /root/.pip
 cat > /root/.pip/pip.conf <<'PIP'
[global]
index-url = https://mirrors.aliyun.com/pypi/simple/
trusted-host = mirrors.aliyun.com
timeout = 120
PIP
 ok "基础工具安装完成，pip镜像阿里云"
}

# ==============================================
# Docker 镜像加速（JSON合并，不覆盖原有配置）
# ==============================================
setup_docker_mirror() {
 if ! command -v docker &>/dev/null; then return; fi
 info "合并Docker国内镜像加速配置"
 mkdir -p /etc/docker
 local new_mirrors='["https://docker.1ms.run","https://docker.xuanyuan.me","https://dockerpull.com"]'
 if [ -f /etc/docker/daemon.json ]; then
 python3 -c "
import json
try:
 cfg = json.load(open('/etc/docker/daemon.json','r'))
except:
 cfg = {}
mirrors = cfg.get('registry-mirrors', [])
add_list = ['https://docker.1ms.run','https://docker.xuanyuan.me','https://dockerpull.com']
for m in add_list:
 if m not in mirrors:
 mirrors.append(m)
cfg['registry-mirrors'] = mirrors
json.dump(cfg, open('/etc/docker/daemon.json','w'), indent=2)
"
 else
 cat > /etc/docker/daemon.json <<DOCKER
{
 "registry-mirrors": [
 "https://docker.1ms.run",
 "https://docker.xuanyuan.me",
 "https://dockerpull.com"
 ]
}
DOCKER
 fi
 systemctl daemon-reload
 systemctl restart docker 2>/dev/null || true
 ok "Docker镜像源合并完成，不覆盖原有配置"
}

# ==============================================
# Maven 打包项目
# ==============================================
build_project() {
 info "项目构建目录: $PROJECT_DIR"
 cd "$PROJECT_DIR"
 [ ! -f pom.xml ] && { err "未找到pom.xml，终止构建"; exit 1; }
 ! command -v java &>/dev/null && { err "Java未安装"; exit 1; }
 info "正在构建项目 (Maven clean package)..."
 info " -> 下载依赖并编译 (首次约 3-8 分钟)..."
 mvn clean package -DskipTests --no-transfer-progress || {
 warn "构建失败，尝试完整日志..."
 mvn clean package -DskipTests || { err "项目构建失败"; exit 1; }
 }
 local jar_file=$(ls target/*.jar 2>/dev/null | head -1)
 [ -z "$jar_file" ] && { err "无打包jar文件"; exit 1; }
 local jar_size=$(du -h "$jar_file" | cut -f1)
 ok "打包成功: $(basename "$jar_file") (${jar_size})"
}

# ==============================================
# Systemd 服务创建
# ==============================================
setup_service() {
 info "注册systemd服务 tg-federal-bot"
 local DEPLOY_DIR="/opt/tg-federal-bot"
 mkdir -p "${DEPLOY_DIR}/target"
 local JAR_FILE=$(ls "${PROJECT_DIR}/target"/*.jar 2>/dev/null | head -1)
 [ -z "$JAR_FILE" ] && { err "缺少jar包，无法创建服务"; return 1; }
 cp "$JAR_FILE" "${DEPLOY_DIR}/target/"
 local JAR_NAME=$(basename "$JAR_FILE")
 id -u tgf &>/dev/null || useradd -r -s /usr/sbin/nologin -d "$DEPLOY_DIR" tgf
 chown -R tgf:tgf "$DEPLOY_DIR"
 cat > /etc/systemd/system/tg-federal-bot.service <<SERVICE
[Unit]
Description=TG Federal Governance Bot
After=network.target postgresql.service redis-server.service
Wants=postgresql.service redis-server.service

[Service]
Type=simple
User=tgf
Group=tgf
WorkingDirectory=${DEPLOY_DIR}
ExecStart=/usr/bin/java \\
 -Dspring.config.additional-location=/etc/tg-federal-bot/ \\
 -jar ${DEPLOY_DIR}/target/${JAR_NAME}
Restart=always
RestartSec=10
LimitNOFILE=65536
Environment="SPRING_CONFIG_ADDITIONAL_LOCATION=/etc/tg-federal-bot/"

[Install]
WantedBy=multi-user.target
SERVICE
 systemctl daemon-reload
 systemctl enable tg-federal-bot
 ok "服务注册完成，开机自启"
}

# ==============================================
# 防火墙放行端口
# ==============================================
setup_firewall() {
 if command -v ufw &>/dev/null && ufw status | grep -qw active; then
 ufw allow 8080/tcp comment 'TG Bot'
 ufw allow 5432/tcp comment 'PG'
 ufw allow 6379/tcp comment 'Redis'
 ufw allow 9900/tcp comment 'ES'
 ok "UFW端口放行"
 elif command -v firewall-cmd &>/dev/null; then
 firewall-cmd --permanent --add-port=8080/tcp
 firewall-cmd --permanent --add-port=5432/tcp
 firewall-cmd --permanent --add-port=6379/tcp
 firewall-cmd --permanent --add-port=9900/tcp
 firewall-cmd --reload
 ok "firewalld放行端口"
 fi
}

# ==============================================
# Webhook 注册（复用全局代理变量，无硬编码端口）
# ==============================================
setup_webhook() {
 [ -z "$WEBHOOK_URL" ] && return
 info "使用本地代理 ${PROXY_FULL} 注册Webhook"
 local hook_addr="${WEBHOOK_URL}/webhook/"
 local curl_arg=(
 "-s" "-X" "POST"
 "--socks5-hostname" "${PROXY_SOCKS_HOST}:${PROXY_SOCKS_PORT}"
 "https://api.telegram.org/bot${BOT_TOKEN}/setWebhook"
 "--data" "url=${hook_addr}"
 )
 if ! curl "${curl_arg[@]}"; then
 warn "代理注册失败，尝试直连"
 curl -s -X POST "https://api.telegram.org/bot${BOT_TOKEN}/setWebhook" --data "url=${hook_addr}"
 fi
 ok "Webhook地址：${hook_addr}"
}

# ==============================================
# Ollama 自动部署
# ==============================================
install_ollama() {
 info "部署 Ollama 本地LLM"
 if ! command -v ollama &>/dev/null; then
 curl -fsSL https://mirror.ghproxy.com/https://ollama.com/install.sh | sh 2>/dev/null || {
 local arch=$(uname -m)
 [[ "$arch" == "x86_64" ]] && arch="amd64" || arch="arm64"
 local ollama_tgz="/tmp/ollama.tgz"
 curl -fsSL --max-time 180 "https://mirror.ghproxy.com/https://github.com/ollama/ollama/releases/latest/download/ollama-linux-${arch}.tgz" -o "$ollama_tgz"
 tar -C /usr/local -xzf "$ollama_tgz"
 cat > /etc/systemd/system/ollama.service <<'SVC'
[Unit]
Description=Ollama LLM Service
After=network.target
[Service]
ExecStart=/usr/local/bin/ollama serve
Restart=on-failure
RestartSec=5
[Install]
WantedBy=multi-user.target
SVC
 systemctl daemon-reload
 }
 systemctl enable --now ollama
 ok "Ollama服务启动"
 fi
 info "等待Ollama就绪"
 local wait_ok=false
 for i in {1..30}; do
 if curl -s "${LOCAL_OLLAMA_URL}/api/tags" &>/dev/null; then
 wait_ok=true
 break
 fi
 sleep 2
 done
 if [ "$wait_ok" = false ]; then
 warn "Ollama超时，手动拉取模型"
 return
 fi
 if ! curl -s "${LOCAL_OLLAMA_URL}/api/tags" | grep -q "${LOCAL_MODEL}"; then
 ollama pull "${LOCAL_MODEL}" || warn "模型拉取失败，手动执行 ollama pull ${LOCAL_MODEL}"
 ok "模型下载完成"
 else
 ok "本地已有模型 ${LOCAL_MODEL}"
 fi
}

# ==============================================
# 主流程入口
# ==============================================
main() {
 echo ""
 echo "╔════════════════════════════════════════════════════╗"
 echo "║ TG联邦治理机器人 - 内置Clash Meta本地代理完整版 ║"
 echo "║ $([ "$PROXY_ENABLED" = "true" ] && echo "自动搭建\${PROXY_FULL}，国内VPS直通TG API" || echo "服务器直连外网，无需代理") ║"
 echo "╚════════════════════════════════════════════════════╝"
 echo ""

 detect_os

    # 检测网络连通性
    info "检查网络连通性..."
    if ping -c 1 -W 3 google.com &>/dev/null || ping -c 1 -W 3 8.8.8.8 &>/dev/null || curl -s --connect-timeout 5 https://api.telegram.org/bot/dummy/getMe &>/dev/null; then
        ok "服务器可直接访问外网，跳过代理安装"
        PROXY_ENABLED="false"
        # 还原 global 变量
        CLASH_SUBSCRIBE=""
    else
        warn "服务器无法直连外网，需要安装代理"
        # 第一步先部署Clash代理，保证后续所有TG相关操作有网络
        install_clash_meta
    fi

 interactive_config
 write_config

 echo -e "\n==================== 1/6 安装系统基础工具 ===================="
 install_tools

 echo -e "\n==================== 2/6 JDK21 运行环境 ===================="
 install_java

 echo -e "\n==================== 3/6 Maven 构建工具 ===================="
 install_maven

 echo -e "\n==================== 4/6 数据库、缓存、Docker镜像加速 ===================="
 install_postgres
 install_redis
 setup_docker_mirror

 # 本地Ollama安装
 if [[ "$MODERATION_PROVIDER" == "local" && "$INSTALL_OLLAMA" =~ ^[yY] ]]; then
 echo -e "\n========= 部署Ollama本地大模型 ========="
 install_ollama
 fi

 echo -e "\n==================== 5/6 Elasticsearch评分引擎 ===================="
 if [[ ! "$INSTALL_ES" =~ ^[nN] ]]; then
 install_es
 else
 info "跳过ES安装"
 fi

 echo -e "\n==================== 6/6 项目打包 + Systemd服务 ===================="
 build_project
 setup_service
 setup_firewall
 setup_webhook

 echo -e "\n╔════════════════════════════════════════════════════╗"
 echo "║ 部署全部完成！内置Clash Meta代理已开机自启 ║"
 echo "╠════════════════════════════════════════════════════╣"
 echo "║ 启动Bot服务: systemctl start tg-federal-bot ║"
 echo "║ 实时运行日志: journalctl -fu tg-federal-bot ║"
 echo "║ Clash代理管理命令: kc start / kc stop / kc status ║"
 echo "║ 代理socks地址: ${PROXY_FULL} ║"
 if [ "$PROXY_ENABLED" = "true" ]; then
 echo "║ 代理socks地址: ${PROXY_FULL} ║"
 fi
 echo "╚════════════════════════════════════════════════════╝"
 warn "首次启动自动执行Flyway数据库迁移，观察日志无报错即可正常收发TG消息"
}

main
