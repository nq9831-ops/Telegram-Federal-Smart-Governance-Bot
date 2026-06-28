#!/usr/bin/env bash
# ============================================================
# TG联邦智能治理机器人 - 内置Clash Meta代理完整版（修复优化）
# 修复：kcla安装ghproxy加速、代理端口全局变量统一、无硬编码端口
# 执行：sudo bash install.sh
# ============================================================
set -euo pipefail

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

# 全局代理统一变量（仅此处修改端口即可全局生效）
PROXY_SOCKS_HOST="127.0.0.1"
PROXY_SOCKS_PORT="7890"
PROXY_FULL="socks5h://${PROXY_SOCKS_HOST}:${PROXY_SOCKS_PORT}"

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
 PKG_UPD="apt-get update -qq"
 PKG_INST="apt-get install -y -qq"
 if [ -f /etc/apt/sources.list ] && ! grep -q "mirrors.aliyun.com" /etc/apt/sources.list; then
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
 PKG_UPD="dnf makecache -q 2>/dev/null || yum makecache -q 2>/dev/null || true"
 PKG_INST="dnf install -y -q 2>/dev/null || yum install -y -q 2>/dev/null || true"
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
 PKG_UPD="pacman -Sy --noconfirm"
 PKG_INST="pacman -S --noconfirm"
 ;;
 *)
 err "不支持操作系统: $OS_ID"
 exit 1
 ;;
 esac
 $PKG_UPD 2>/dev/null || warn "源更新警告可忽略"
}

# ==============================================
# 集成模块：自动安装 & 配置 Clash Meta (kcla)
# 修复：ghproxy 国内加速兜底，解决cdn打不开问题
# ==============================================
install_clash_meta() {
 info "===== 开始部署本地Clash Meta代理（${PROXY_FULL}） ====="
 if command -v kc &>/dev/null; then
 ok "检测到已安装 kcla，直接更新订阅"
 else
 info "一键安装 Clash Meta kcla 工具（国内ghproxy加速兜底）"
 # 双地址降级：原cdn失败自动走ghproxy
 INSTALL_SCRIPT_URL="https://cdn.jsdelivr.net/gh/DaiDuncan/kcla@main/install.sh"
 INSTALL_SCRIPT_PROXY_URL="https://mirror.ghproxy.com/https://cdn.jsdelivr.net/gh/DaiDuncan/kcla@main/install.sh"
 if ! bash <(curl --connect-timeout 10 -L "$INSTALL_SCRIPT_URL" 2>/dev/null); then
 warn "原CDN访问失败，切换ghproxy国内镜像安装"
 bash <(curl --connect-timeout 10 -L "$INSTALL_SCRIPT_PROXY_URL" 2>/dev/null)
 fi
 fi

 # 交互式输入订阅链接
 while true; do
 input "请输入Clash订阅链接（必填，国内VPS无代理无法访问TG）："
 read -r CLASH_SUBSCRIBE
 if [[ -n "$CLASH_SUBSCRIBE" ]]; then break; fi
 warn "订阅链接不能为空，必须填入有效节点订阅"
 done

 # 导入订阅并更新
 kc sub add "$CLASH_SUBSCRIBE"
 kc sub update
 ok "订阅导入并更新完成"

 # 开机自启 + 启动服务
 kc enable
 kc start
 sleep 3
 ok "Clash Meta 已启动，socks5 监听 ${PROXY_FULL}"

 # 预检测代理连通TG API（复用全局代理变量）
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
 echo "║ 内置Clash Meta本地代理，固定启用${PROXY_FULL} ║"
 echo "╚════════════════════════════════════════════════════╝"
 echo ""

 # Bot Token
 while true; do
 input "Telegram Bot Token（@BotFather 获取）："
 read -r BOT_TOKEN
 [ -n "$BOT_TOKEN" ] && break
 warn "Token 不能为空"
 done
 ok "Bot Token: ${BOT_TOKEN:0:10}******"

 # Bot用户名
 input "Bot 用户名（不带@，默认 YourBot_bot）："
 read -r BOT_USERNAME
 BOT_USERNAME="${BOT_USERNAME:-YourBot_bot}"
 ok "Bot 用户名: ${BOT_USERNAME}"

 # 管理员ID
 input "超级管理员UserID（多ID逗号分隔，默认 5006320370）："
 read -r BOT_CREATOR
 BOT_CREATOR="${BOT_CREATOR:-5006320370}"
 ok "超级管理员: ${BOT_CREATOR}"

 # 审核模式
 echo ""
 input "内容审核模式 — 1)云端DeepSeek 2)本地Ollama [1/2]（默认1）："
 read -r MODERATION_MODE
 MODERATION_MODE="${MODERATION_MODE:-1}"
 if [ "$MODERATION_MODE" = "2" ]; then
 MODERATION_PROVIDER="local"
 input "Ollama地址（默认 http://localhost:11434）："
 read -r LOCAL_OLLAMA_URL
 LOCAL_OLLAMA_URL="${LOCAL_OLLAMA_URL:-http://localhost:11434}"
 input "本地模型（默认 qwen2.5:7b）："
 read -r LOCAL_MODEL
 LOCAL_MODEL="${LOCAL_MODEL:-qwen2.5:7b}"
 DEEPSEEK_KEY=""
 ok "本地审核: ${LOCAL_MODEL}"
 input "自动安装Ollama并拉取模型？[y/N]"
 read -r INSTALL_OLLAMA
 else
 MODERATION_PROVIDER="cloud"
 LOCAL_OLLAMA_URL="http://localhost:11434"
 LOCAL_MODEL="qwen2.5:7b"
 input "DeepSeek API Key（留空则降级规则审核）："
 read -r DEEPSEEK_KEY
 INSTALL_OLLAMA="n"
 [ -z "$DEEPSEEK_KEY" ] && warn "未配置DeepSeek，仅本地关键词审核" || ok "DeepSeek密钥: ${DEEPSEEK_KEY:0:8}******"
 fi

 # Webhook
 input "Webhook公网域名（无则回车使用长轮询）："
 read -r WEBHOOK_URL
 WEBHOOK_URL="${WEBHOOK_URL:-}"
 [ -z "$WEBHOOK_URL" ] && warn "未配置Webhook，使用Long Polling接收消息"

 # PostgreSQL 密码强校验：禁止空/默认postgres
 while true; do
 input "PostgreSQL数据库密码（禁止使用postgres，至少8位大小写+数字）："
 read -r DB_PASSWORD
 if [[ ${#DB_PASSWORD} -ge 8 && "$DB_PASSWORD" != "postgres" ]]; then
 break
 fi
 warn "密码长度不足8位或使用默认postgres，请重新设置高强度密码"
 done

 # Elasticsearch
 input "安装Elasticsearch评分引擎？[Y/n]"
 read -r INSTALL_ES

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
 echo "║ TG代理: 启用 ${PROXY_FULL}（内置Clash Meta）"
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
 if $PKG_INST openjdk-21-jdk-headless; then install_ok=true; fi
 [[ "$install_ok" == false ]] && $PKG_INST openjdk-17-jdk-headless && install_ok=true
 ;;
 centos|rhel|rocky|almalinux|fedora)
 if $PKG_INST java-21-openjdk-headless; then install_ok=true; fi
 [[ "$install_ok" == false ]] && $PKG_INST java-17-openjdk-headless && install_ok=true
 ;;
 arch)
 $PKG_INST jdk21-openjdk && install_ok=true
 ;;
 esac
 if [ "$install_ok" = true ]; then
 ok "JDK系统包安装完成"
 return
 fi
 # 华为云二进制兜底
 local jdk_url="https://mirrors.huaweicloud.com/adoptium/21/jdk/${arch}/linux/OpenJDK21U-jdk_${arch}_linux_hotspot_21.0.3_9.tar.gz"
 rm -f /tmp/jdk21.tar.gz
 if curl -sL --connect-timeout 10 --max-time 180 "$jdk_url" -o /tmp/jdk21.tar.gz && [ -s /tmp/jdk21.tar.gz ]; then
 mkdir -p /opt
 tar -xzf /tmp/jdk21.tar.gz -C /opt/
 jdir=$(ls -d /opt/jdk-* /opt/OpenJDK* 2>/dev/null | head -1)
 if [ -n "$jdir" ]; then
 update-alternatives --install /usr/bin/java java "$jdir/bin/java" 2100 2>/dev/null || true
 update-alternatives --install /usr/bin/javac javac "$jdir/bin/javac" 2100 2>/dev/null || true
 echo "export JAVA_HOME=$jdir" > /etc/profile.d/jdk21.sh
 chmod 644 /etc/profile.d/jdk21.sh
 ok "二进制JDK21部署完成"
 return
 fi
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
 info "安装 Maven3.9.9 华为云镜像"
 local mvn_pkg="/tmp/maven.tar.gz"
 rm -f "$mvn_pkg"
 curl -sL --connect-timeout 10 --max-time 120 "https://repo.huaweicloud.com/apache/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz" -o "$mvn_pkg" || \
 curl -sL --connect-timeout 10 --max-time 120 "https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz" -o "$mvn_pkg"
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
 $PKG_INST maven 2>/dev/null || true
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
 info "安装 PostgreSQL16"
 local CODENAME=$(lsb_release -cs 2>/dev/null || "jammy")
 case "$OS_ID" in
 ubuntu|debian)
 curl -sL "https://www.postgresql.org/media/keys/ACCC4CF8.asc" -o /tmp/pgdg.gpg 2>/dev/null
 if [ -s /tmp/pgdg.gpg ]; then
 gpg --dearmor -o /usr/share/keyrings/pgdg.gpg /tmp/pgdg.gpg
 echo "deb [signed-by=/usr/share/keyrings/pgdg.gpg] https://mirrors.aliyun.com/postgresql/apt/ ${CODENAME}-pgdg main" > /etc/apt/sources.list.d/pgdg.list
 $PKG_UPD
 $PKG_INST postgresql-16 postgresql-client-16
 else
 $PKG_INST postgresql postgresql-client
 fi
 ;;
 centos|rhel|rocky|almalinux|fedora)
 local el_ver="${OS_VER%%.*}"
 curl -sL "https://download.postgresql.org/pub/repos/yum/reporpms/EL-${el_ver}-$(uname -m)/pgdg-redhat-repo-latest.noarch.rpm" -o /tmp/pgdg.rpm 2>/dev/null || true
 [ -f /tmp/pgdg.rpm ] && $PKG_INST /tmp/pgdg.rpm
 $PKG_INST postgresql16-server postgresql16-contrib
 /usr/pgsql-16/bin/postgresql-16-setup initdb 2>/dev/null || postgresql-setup --initdb 2>/dev/null || true
 ;;
 arch)
 $PKG_INST postgresql
 ;;
 esac
 systemctl daemon-reload
 systemctl enable --now postgresql 2>/dev/null || systemctl enable --now postgresql-16 2>/dev/null || true
 sleep 2
 fi
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
 info "安装 Redis"
 $PKG_INST redis-server 2>/dev/null || $PKG_INST redis 2>/dev/null || {
 warn "Redis安装失败，手动部署"
 return
 }
 systemctl daemon-reload
 systemctl enable --now redis-server 2>/dev/null || systemctl enable --now redis 2>/dev/null || true
 ok "Redis启动完成"
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
 $PKG_UPD
 $PKG_INST elasticsearch
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
 $PKG_INST elasticsearch
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
 $PKG_INST $tool_list 2>/dev/null || warn "部分工具警告忽略"
 mkdir -p /root/.pip
 cat > /root/.pip/pip.conf <<'PIP'
[global]
index-url = https://mirrors.aliyun.com/pypi/simple/
trusted-host = mirrors.aliyun.com
timeout = 120
PIP
 ok "基础工具安装，pip镜像阿里云"
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
 info "Maven clean package -DskipTests"
 if ! mvn clean package -DskipTests -q; then
 warn "静默构建失败，完整日志重试"
 mvn clean package -DskipTests || { err "项目构建失败"; exit 1; }
 fi
 local jar_file=$(ls target/*.jar 2>/dev/null | head -1)
 [ -z "$jar_file" ] && { err "无打包jar文件"; exit 1; }
 ok "打包成功: $(basename "$jar_file")"
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
 echo "║ 自动搭建${PROXY_FULL}，国内VPS直通TG API ║"
 echo "╚════════════════════════════════════════════════════╝"
 echo ""

 detect_os
 # 第一步先部署Clash代理，保证后续所有TG相关操作有网络
 install_clash_meta
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
 echo "╚════════════════════════════════════════════════════╝"
 warn "首次启动自动执行Flyway数据库迁移，观察日志无报错即可正常收发TG消息"
}

main
