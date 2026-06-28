#!/usr/bin/env bash
# ============================================================
# TG联邦智能治理机器人 - 一键部署脚本（适配国内服务器）
# 支持: Ubuntu 20.04+ / Debian 11+ / CentOS 7+ / Fedora
# 所有安装包均从国内镜像下载，交互式配置
# 使用方法: sudo bash deploy/install.sh
# ============================================================
set -euo pipefail

# ─── 颜色 ───
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${BLUE}[INFO]${NC} $1"; }
ok()    { echo -e "${GREEN}[OK]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
err()   { echo -e "${RED}[ERR]${NC} $1" >&2; }
input() { echo -e "${CYAN}[INPUT]${NC} $1"; }

if [ "$EUID" -ne 0 ]; then err "请使用 sudo 或以 root 运行"; exit 1; fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# ═══════════════════════════════
# 1. 检测系统
# ═══════════════════════════════

detect_os() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        OS_ID="$ID"; OS_LIKE="$ID_LIKE"; OS_VER="${VERSION_ID:-}"
    elif [ -f /etc/debian_version ]; then OS_ID="debian"
    elif [ -f /etc/redhat-release ]; then OS_ID="rhel"
    else OS_ID="unknown"; fi
    info "系统: ${OS_ID} ${OS_VER}"
    case "$OS_ID" in
        ubuntu|debian|kali|linuxmint)
            PKG_UPD="apt-get update -qq"; PKG_INST="apt-get install -y -qq"
            # 替换 apt 源为阿里云
            [ -f /etc/apt/sources.list ] && ! grep -q "mirrors.aliyun.com" /etc/apt/sources.list && {
                cp /etc/apt/sources.list /etc/apt/sources.list.bak 2>/dev/null || true
                CODENAME=$(lsb_release -cs 2>/dev/null || echo "jammy")
                if [ "$OS_ID" = "ubuntu" ]; then
                    cat > /etc/apt/sources.list <<EOF
deb https://mirrors.aliyun.com/ubuntu/ $CODENAME main restricted universe multiverse
deb https://mirrors.aliyun.com/ubuntu/ $CODENAME-security main restricted universe multiverse
deb https://mirrors.aliyun.com/ubuntu/ $CODENAME-updates main restricted universe multiverse
EOF
                elif [ "$OS_ID" = "debian" ]; then
                    cat > /etc/apt/sources.list <<EOF
deb https://mirrors.aliyun.com/debian/ $CODENAME main contrib non-free
deb https://mirrors.aliyun.com/debian/ $CODENAME-updates main contrib non-free
deb https://mirrors.aliyun.com/debian-security ${CODENAME}-security main contrib non-free
EOF
                fi
                ok "apt 切换为阿里云镜像"
            } ;;
        fedora|rhel|centos|rocky|almalinux)
            PKG_UPD="dnf makecache -q 2>/dev/null || yum makecache -q 2>/dev/null || true"
            PKG_INST="dnf install -y -q 2>/dev/null || yum install -y -q 2>/dev/null || true"
            if [ -f /etc/yum.repos.d/CentOS-Base.repo ] && ! grep -q "mirrors.aliyun.com" /etc/yum.repos.d/CentOS-Base.repo 2>/dev/null; then
                curl -sL -o /etc/yum.repos.d/CentOS-Base.repo "https://mirrors.aliyun.com/repo/Centos-${OS_VER%%.*}.repo" 2>/dev/null || true
                ok "yum 切换为阿里云镜像"
            fi ;;
        arch|manjaro)
            PKG_UPD="pacman -Sy --noconfirm"; PKG_INST="pacman -S --noconfirm" ;;
        *) err "未知系统: $OS_ID"; exit 1 ;;
    esac
    $PKG_UPD 2>/dev/null || true
}

# ═══════════════════════════════
# 2. Maven 阿里云镜像配置（全局 settings.xml）
# ═══════════════════════════════

setup_maven_mirror() {
    mkdir -p /root/.m2
    cat > /root/.m2/settings.xml <<'MAVEN'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0
                              https://maven.apache.org/xsd/settings-1.2.0.xsd">
    <mirrors>
        <mirror><id>aliyun</id><name>Aliyun Mirror</name>
            <url>https://maven.aliyun.com/repository/public</url><mirrorOf>*</mirrorOf></mirror>
    </mirrors>
    <interactiveMode>false</interactiveMode>
</settings>
MAVEN
    ok "Maven 阿里云镜像已配置"
    # 也配置项目内 pom.xml 的 repositories
    if grep -q "maven.aliyun.com" "${PROJECT_DIR}/pom.xml" 2>/dev/null; then
        ok "pom.xml 已有阿里云镜像"
    fi
}

# ═══════════════════════════════
# 3. 交互式配置
# ═══════════════════════════════

interactive_config() {
    echo ""
    echo "╔════════════════════════════════════════════════════╗"
    echo "║     TG联邦智能治理机器人 - 部署配置向导          ║"
    echo "╚════════════════════════════════════════════════════╝"
    echo ""

    # Token
    while [ -z "${BOT_TOKEN:-}" ]; do
        input "Telegram Bot Token（从 @BotFather 获取）："
        read -r BOT_TOKEN; [ -z "$BOT_TOKEN" ] && warn "不能为空！"
    done
    ok "Bot Token: ${BOT_TOKEN:0:10}******"

    # 用户名
    input "Bot 用户名（不含 @，默认 YourBot_bot）："
    read -r BOT_USERNAME; BOT_USERNAME="${BOT_USERNAME:-YourBot_bot}"
    ok "Bot 用户名: ${BOT_USERNAME}"

    # 超级管理员
    input "超级管理员 User ID（多个逗号分隔，默认 5006320370）："
    read -r BOT_CREATOR; BOT_CREATOR="${BOT_CREATOR:-5006320370}"
    ok "超级管理员: ${BOT_CREATOR}"

    # 内容审核模式
    echo ""
    input "内容审核模式 — 1) 云端 DeepSeek  2) 本地 Ollama（数据不外出）[1/2]（默认 1）："
    read -r MODERATION_MODE; MODERATION_MODE="${MODERATION_MODE:-1}"
    if [ "$MODERATION_MODE" = "2" ]; then
        MODERATION_PROVIDER="local"
        input "本地 Ollama 地址（默认 http://localhost:11434）："
        read -r LOCAL_OLLAMA_URL; LOCAL_OLLAMA_URL="${LOCAL_OLLAMA_URL:-http://localhost:11434}"
        input "本地模型名称（默认 qwen2.5:7b，可选 deepseek-r1:7b）："
        read -r LOCAL_MODEL; LOCAL_MODEL="${LOCAL_MODEL:-qwen2.5:7b}"
        DEEPSEEK_KEY=""
        ok "本地模型: ${LOCAL_MODEL}（${LOCAL_OLLAMA_URL}）"

        # 询问是否安装 Ollama + 模型
        input "安装 Ollama 并下载 ${LOCAL_MODEL}？（约 4-8GB）[y/N]"
        read -r INSTALL_OLLAMA
    else
        MODERATION_PROVIDER="cloud"
        LOCAL_OLLAMA_URL="http://localhost:11434"
        LOCAL_MODEL="qwen2.5:7b"
        input "DeepSeek API Key（可选，回车跳过→规则引擎降级）："
        read -r DEEPSEEK_KEY; DEEPSEEK_KEY="${DEEPSEEK_KEY:-}"
        [ -z "$DEEPSEEK_KEY" ] && warn "未配置 DeepSeek，将使用规则引擎降级" || ok "DeepSeek Key: ${DEEPSEEK_KEY:0:8}******"
        INSTALL_OLLAMA=""
    fi

    # Webhook
    input "Webhook 域名（可选，回车跳过）："
    read -r WEBHOOK_URL; WEBHOOK_URL="${WEBHOOK_URL:-}"
    [ -z "$WEBHOOK_URL" ] && warn "未配置 Webhook，部署后需手动设置"

    # SOCKS5 代理（国内服务器必配）
    echo ""
    input "配置 SOCKS5 代理（访问 Telegram API 需要）？[Y/n]"
    read -r USE_PROXY
    if [[ "$USE_PROXY" =~ ^[nN] ]]; then
        PROXY_ENABLED="false"; PROXY_HOST=""; PROXY_PORT=""
    else
        PROXY_ENABLED="true"
        input "代理地址（默认 127.0.0.1）："; read -r tmp
        PROXY_HOST="${tmp:-127.0.0.1}"
        input "代理端口（默认 7890）："; read -r tmp
        PROXY_PORT="${tmp:-7890}"
        ok "SOCKS5: ${PROXY_HOST}:${PROXY_PORT}"
    fi

    # 数据库密码
    input "PostgreSQL 密码（默认 postgres）："; read -r tmp
    DB_PASSWORD="${tmp:-postgres}"

    # Elasticsearch
    input "需要安装 Elasticsearch（评分引擎需要）？[Y/n]"
    read -r INSTALL_ES

    # 确认
    echo ""
    echo "╔════════════════════════════════════════════════════╗"
    echo "║  配置确认                                         ║"
    echo "╠════════════════════════════════════════════════════╣"
    echo "║ Token:    ${BOT_TOKEN:0:15}******"
    echo "║ Bot:      ${BOT_USERNAME}"
    echo "║ 管理员:   ${BOT_CREATOR}"
    echo "║ 审核:     $([ "$MODERATION_PROVIDER" = "local" ] && echo "本地 ${LOCAL_MODEL}" || echo "云端 DeepSeek")"
    echo "║ Webhook:  $([ -n "$WEBHOOK_URL" ] && echo "$WEBHOOK_URL" || echo '未配置')"
    echo "║ SOCKS5:   $([ "$PROXY_ENABLED" = "true" ] && echo "${PROXY_HOST}:${PROXY_PORT}" || echo '不使用')"
    echo "║ ES:       $([[ "$INSTALL_ES" =~ ^[nN] ]] && echo '不安装' || echo '安装')"
    echo "╚════════════════════════════════════════════════════╝"
    echo ""
    input "确认部署？[Y/n]"; read -r CONFIRM
    [[ "$CONFIRM" =~ ^[nN] ]] && { warn "取消部署"; exit 0; }
}

# ═══════════════════════════════
# 4. 写入 application.properties
# ═══════════════════════════════

write_config() {
    info "写入 application.properties（安全加固：不再写入源码目录）..."
    mkdir -p /etc/tg-federal-bot
    cat > /etc/tg-federal-bot/application.properties <<PROPS
spring.application.name=tg-federal-bot
server.port=8080

# ===== Telegram Bot =====
bot.token=${BOT_TOKEN}
bot.username=${BOT_USERNAME}
bot.creator=${BOT_CREATOR}
bot.webhook-url=${WEBHOOK_URL}
bot.webhook-secret=
bot.reviewers=                         # 审核官ID，逗号分隔

# ===== SOCKS5 代理（国内服务器访问 Telegram API）=====
bot.proxy.enabled=${PROXY_ENABLED}
bot.proxy.type=SOCKS
bot.proxy.host=${PROXY_HOST}
bot.proxy.port=${PROXY_PORT}

# ===== PostgreSQL =====
spring.datasource.url=jdbc:postgresql://localhost:5432/tg_federal_bot
spring.datasource.username=postgres
spring.datasource.password=${DB_PASSWORD}
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=10000
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true

# ===== Redis =====
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.timeout=3000
spring.cache.type=redis
spring.cache.redis.time-to-live=300s
spring.cache.redis.cache-null-values=false

# ===== Elasticsearch（评分用，端口 9900）=====
elasticsearch.uris=http://localhost:9900
elasticsearch.rating-index=rating_v2

# ===== 内容审核 =====
moderation.provider=${MODERATION_PROVIDER}
moderation.local.api-url=${LOCAL_OLLAMA_URL}/v1/chat/completions
moderation.local.model=${LOCAL_MODEL}

# ===== 入群验证 =====
captcha.required-for-new-members=true
captcha.arithmetic-enabled=true
captcha.failed-attempt-limit=3

deepseek.api-key=${DEEPSEEK_KEY}
deepseek.api-url=https://api.deepseek.com/v1/chat/completions
deepseek.model=deepseek-chat
deepseek.timeout-ms=10000
deepseek.max-retries=3
deepseek.fallback-engine=true

# ===== 信用分 =====
credit.initial=100
credit.daily-auto-increment=1
credit.max-score=100
credit.global-mute-threshold=50
credit.privilege-freeze-threshold=60
credit.punish.scam=100
credit.punish.political=100
credit.punish.porn-cross=30
credit.punish.gambling-cross=20
credit.punish.ad=5
credit.punish.ad-variance=true

# ===== DeepSeek 阈值 =====
deepseek.auto-execute-threshold=0.85
deepseek.human-review-threshold=0.60

# ===== 冷启动（首次部署建议开启）=====
system.cold-start-days=7
system.cold-start=true

# ===== 存储策略 =====
storage.message-retention-days=90
storage.audit-log-retention-days=180

# ===== 限流 =====
rate-limit.ip.max-per-second=50
rate-limit.ip.ban-seconds=3600
rate-limit.user.max-per-minute=10
rate-limit.sensitive.max-per-minute=30

# ===== 排行榜 =====
ranking.top-1-pct-reward=5
ranking.top-2-5-pct-reward=3
ranking.top-6-15-pct-reward=1
ranking.bottom-5-pct-penalty=-5
ranking.bottom-6-15-pct-penalty=-2

# ===== 邀请 =====
invite.max-daily-codes=10
invite.user-credit-reward=2
invite.group-credit-reward=5
invite.bot-credit-reward=3

# ===== 功能开关 =====
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

# ===== Actuator =====
management.endpoints.web.exposure.include=health,prometheus,info
management.metrics.export.prometheus.enabled=true

# ===== 日志 =====
logging.level.com.tgf.bot=INFO
logging.level.org.springframework=WARN
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n
PROPS
    ok "application.properties 写入完成"
    # 写入安全配置到 /etc（覆盖敏感值）
    cat > /etc/tg-federal-bot/application.properties <<ETCPROPS
# 安全配置文件 - 覆盖源码中的默认值
# 此文件不提交到 Git
spring.datasource.password=${DB_PASSWORD}
deepseek.api-key=${DEEPSEEK_KEY}
ETCPROPS
    ok "安全配置已写入 /etc/tg-federal-bot/application.properties"
}

# ═══════════════════════════════
# 5. 安装 Java 21（国内镜像）
# ═══════════════════════════════

install_java() {
    if command -v java &>/dev/null; then
        jver=$(java -version 2>&1 | head -1 | grep -oP '"\K\d+' | head -1)
        [ -n "$jver" ] && [ "$jver" -ge 21 ] 2>/dev/null && { ok "Java $jver 已安装"; return; }
    fi
    info "安装 JDK 21..."

    # 策略1: 通过包管理器安装 openjdk-21
    case "$OS_ID" in
        ubuntu|debian)
            $PKG_INST openjdk-21-jdk-headless 2>/dev/null && { ok "JDK 21 安装完成（apt）"; return; }
            $PKG_INST openjdk-17-jdk-headless 2>/dev/null && { ok "JDK 17 已安装（备选）"; return; }
            ;;
        centos|rhel|almalinux|rocky)
            $PKG_INST java-21-openjdk-headless 2>/dev/null && { ok "JDK 21 安装完成（yum）"; return; }
            $PKG_INST java-17-openjdk-headless 2>/dev/null && { ok "JDK 17 已安装（备选）"; return; }
            ;;
        fedora)
            $PKG_INST java-21-openjdk-headless 2>/dev/null && { ok "JDK 21 安装完成"; return; }
            ;;
        arch)
            $PKG_INST jdk21-openjdk 2>/dev/null && { ok "JDK 21 安装完成"; return; }
            ;;
    esac

    # 策略2: 从华为云下载 tar.gz
    info "从华为云下载 JDK 21..."
    arch=$(uname -m); [ "$arch" = "aarch64" ] && arch="aarch64" || arch="x64"
    DOWNLOADED=false
    for url in \
        "https://repo.huaweicloud.com/java/jdk/21.0.3+9/OpenJDK21U-jdk_${arch}_linux_hotspot_21.0.3_9.tar.gz" \
        "https://mirrors.huaweicloud.com/homebrew/bottles/openjdk@21-21.0.3.bottle.tar.gz"; do
        curl -sL --connect-timeout 10 --max-time 120 "$url" -o /tmp/jdk21.tar.gz && {
            file /tmp/jdk21.tar.gz | grep -q "gzip\|Zip" && { DOWNLOADED=true; break; }
        }
        rm -f /tmp/jdk21.tar.gz
    done

    if [ "$DOWNLOADED" = true ]; then
        tar xzf /tmp/jdk21.tar.gz -C /opt/
        jdir=$(ls -d /opt/jdk-* /opt/OpenJDK* 2>/dev/null | head -1)
        if [ -n "$jdir" ]; then
            update-alternatives --install /usr/bin/java java "$jdir/bin/java" 2100 2>/dev/null || true
            update-alternatives --install /usr/bin/javac javac "$jdir/bin/javac" 2100 2>/dev/null || true
            echo "export JAVA_HOME=$jdir" > /etc/profile.d/jdk21.sh
            ok "JDK 21 已安装: $jdir"
            return
        fi
    fi

    # 策略3: 使用 Adoptium 阿里云镜像
    info "尝试从阿里云 Adoptium 安装..."
    case "$OS_ID" in
        ubuntu|debian)
            curl -sL "https://mirrors.aliyun.com/Adoptium/GPG-KEY-adoptium" -o /tmp/adoptium.gpg 2>/dev/null || true
            if [ -s /tmp/adoptium.gpg ]; then
                gpg --dearmor -o /usr/share/keyrings/adoptium.gpg /tmp/adoptium.gpg 2>/dev/null || true
                echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://mirrors.aliyun.com/Adoptium/deb $(lsb_release -cs 2>/dev/null || echo 'jammy') main" > /etc/apt/sources.list.d/adoptium.list
                $PKG_UPD 2>/dev/null || true
                $PKG_INST temurin-21-jdk 2>/dev/null && { ok "JDK 21 安装完成（阿里云 Adoptium）"; return; }
            fi
            ;;
        centos|rhel|fedora)
            cat > /etc/yum.repos.d/adoptium.repo <<'REPO'
[Adoptium]
name=Adoptium
baseurl=https://mirrors.aliyun.com/Adoptium/rpm/$(rpm --eval '%{?rhel:el%{rhel}%{?fedora:fedora%{fedora}}')/$(uname -m)
enabled=1
gpgcheck=0
REPO
            $PKG_INST temurin-21-jdk 2>/dev/null && { ok "JDK 21 安装完成（阿里云 Adoptium）"; return; }
            ;;
    esac

    warn "JDK 21 安装可能未完全成功，部署后请检查: java --version"
}

# ═══════════════════════════════
# 6. 安装 Maven（国内镜像）
# ═══════════════════════════════

install_maven() {
    if command -v mvn &>/dev/null; then
        v=$(mvn --version 2>&1 | head -1 | grep -oP '\d+\.\d+\.\d+' | head -1)
        ok "Maven $v 已安装"; return
    fi
    info "安装 Maven 3.9.9（华为云镜像）..."
    curl -sL --connect-timeout 10 --max-time 120 \
        "https://repo.huaweicloud.com/apache/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz" \
        -o /tmp/maven.tar.gz || {
        curl -sL --connect-timeout 10 --max-time 120 \
            "https://mirrors.tuna.tsinghua.edu.cn/apache/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz" \
            -o /tmp/maven.tar.gz
    }

    if [ -f /tmp/maven.tar.gz ] && [ -s /tmp/maven.tar.gz ]; then
        tar xzf /tmp/maven.tar.gz -C /opt/
        ln -sf /opt/apache-maven-3.9.9/bin/mvn /usr/local/bin/mvn
        mvn --version >/dev/null 2>&1 && ok "Maven 3.9.9 安装完成"
    else
        warn "Maven 下载失败，尝试 apt/yum..."
        $PKG_INST maven 2>/dev/null || true
    fi
    setup_maven_mirror
}

# ═══════════════════════════════
# 7. 安装 PostgreSQL（国内镜像）
# ═══════════════════════════════

install_postgres() {
    if command -v psql &>/dev/null; then
        ok "PostgreSQL 已安装"
        systemctl enable postgresql 2>/dev/null || systemctl enable postgresql-16 2>/dev/null || true
        systemctl start postgresql 2>/dev/null || systemctl start postgresql-16 2>/dev/null || true
        return
    fi
    info "安装 PostgreSQL..."

    case "$OS_ID" in
        ubuntu|debian)
            # 阿里云 PG 源
            CODENAME=$(lsb_release -cs 2>/dev/null || echo 'jammy')
            curl -sL "https://mirrors.aliyun.com/postgresql/apt/GPG-KEY-PGDG" -o /tmp/pgdg.gpg 2>/dev/null
            if [ -s /tmp/pgdg.gpg ]; then
                gpg --dearmor -o /usr/share/keyrings/pgdg.gpg /tmp/pgdg.gpg 2>/dev/null || true
                echo "deb [signed-by=/usr/share/keyrings/pgdg.gpg] https://mirrors.aliyun.com/postgresql/apt/ ${CODENAME}-pgdg main" > /etc/apt/sources.list.d/pgdg.list
                $PKG_UPD 2>/dev/null || true
                $PKG_INST postgresql-16 postgresql-client-16 2>/dev/null || $PKG_INST postgresql postgresql-client
            else
                $PKG_INST postgresql postgresql-client
            fi
            ;;
        centos|rhel|fedora|rocky|almalinux)
            dnf install -y -q https://download.postgresql.org/pub/repos/yum/reporpms/EL-8-$(uname -m)/pgdg-redhat-repo-latest.noarch.rpm 2>/dev/null || \
            yum install -y -q https://download.postgresql.org/pub/repos/yum/reporpms/EL-7-$(uname -m)/pgdg-redhat-repo-latest.noarch.rpm 2>/dev/null || true
            $PKG_INST postgresql16-server postgresql16-contrib 2>/dev/null || $PKG_INST postgresql-server postgresql-contrib
            # 初始化（RHEL 系需要）
            /usr/pgsql-16/bin/postgresql-16-setup initdb 2>/dev/null || postgresql-setup --initdb 2>/dev/null || true
            ;;
        arch)
            $PKG_INST postgresql
            ;;
    esac

    # 启动
    systemctl daemon-reload 2>/dev/null || true
    systemctl enable postgresql 2>/dev/null || systemctl enable postgresql-16 2>/dev/null || true
    systemctl start postgresql 2>/dev/null || systemctl start postgresql-16 2>/dev/null || true
    sleep 2

    # 配置密码和数据库
    su - postgres -c "psql -c \"ALTER USER postgres PASSWORD '${DB_PASSWORD}'\"" 2>/dev/null || true
    su - postgres -c "createdb tg_federal_bot" 2>/dev/null || info "数据库已存在"

    # RHEL 系默认 ident 改为 md5
    if [ -d /var/lib/pgsql ]; then
        pg_hba=$(find /var/lib/pgsql -name pg_hba.conf 2>/dev/null | head -1)
        [ -n "$pg_hba" ] && { sed -i 's/peer/md5/g; s/ident/md5/g' "$pg_hba"; systemctl restart postgresql 2>/dev/null || true; }
    fi

    ok "PostgreSQL 配置完成"
}

# ═══════════════════════════════
# 8. 安装 Redis
# ═══════════════════════════════

install_redis() {
    if command -v redis-server &>/dev/null; then
        ok "Redis 已安装"
        systemctl enable redis-server 2>/dev/null || systemctl enable redis 2>/dev/null || true
        systemctl start redis-server 2>/dev/null || systemctl start redis 2>/dev/null || true
        return
    fi
    info "安装 Redis..."
    $PKG_INST redis-server 2>/dev/null || $PKG_INST redis 2>/dev/null || warn "Redis 安装失败，请手动安装"
    if command -v redis-server &>/dev/null; then
        systemctl enable redis-server 2>/dev/null || systemctl enable redis 2>/dev/null || true
        systemctl start redis-server 2>/dev/null || systemctl start redis 2>/dev/null || true
        ok "Redis 已启动"
    fi
}

# ═══════════════════════════════
# 9. 安装 Elasticsearch（国内镜像）
# ═══════════════════════════════

install_es() {
    if command -v elasticsearch &>/dev/null; then
        ok "Elasticsearch 已安装"
        # 确保端口和配置
        configure_es; return
    fi
    info "安装 Elasticsearch（华为云镜像）..."

    case "$OS_ID" in
        ubuntu|debian)
            curl -sL "https://mirrors.huaweicloud.com/elasticstack/GPG-KEY-elasticsearch" -o /tmp/es.gpg 2>/dev/null
            if [ -s /tmp/es.gpg ]; then
                gpg --dearmor -o /usr/share/keyrings/elasticsearch-keyring.gpg /tmp/es.gpg 2>/dev/null || true
                echo "deb [signed-by=/usr/share/keyrings/elasticsearch-keyring.gpg] https://mirrors.huaweicloud.com/elasticstack/8.x/apt stable main" > /etc/apt/sources.list.d/elastic-8.x.list
                $PKG_UPD 2>/dev/null || true
                $PKG_INST elasticsearch
            else
                # 直接下载 deb 包
                curl -sL --connect-timeout 10 --max-time 120 \
                    "https://mirrors.huaweicloud.com/elasticstack/8.x/apt/pool/main/e/elasticsearch/elasticsearch-8.15.0-amd64.deb" \
                    -o /tmp/es.deb 2>/dev/null && dpkg -i /tmp/es.deb 2>/dev/null || \
                warn "ES 安装失败"
            fi
            ;;
        centos|rhel|fedora|rocky|almalinux)
            cat > /etc/yum.repos.d/elastic.repo <<'REPO'
[elastic-8.x]
name=Elasticsearch
baseurl=https://mirrors.huaweicloud.com/elasticstack/8.x/yum
gpgcheck=0
enabled=1
REPO
            $PKG_INST elasticsearch 2>/dev/null || \
            warn "ES 安装失败"
            ;;
        arch)
            $PKG_INST elasticsearch 2>/dev/null || \
            warn "ES 安装失败，Arch 建议用 Docker: docker run -d --name tgf-es -p 9900:9200 elasticsearch:8.15.0"
            ;;
    esac

    configure_es
    systemctl enable elasticsearch 2>/dev/null || true
    systemctl start elasticsearch 2>/dev/null || true
    ok "Elasticsearch 已启动（端口 9900）"
}

configure_es() {
    [ ! -f /etc/elasticsearch/elasticsearch.yml ] && return
    sed -i 's/#http.port: 9200/http.port: 9900/' /etc/elasticsearch/elasticsearch.yml 2>/dev/null || true
    sed -i 's/http.port: [0-9]*/http.port: 9900/' /etc/elasticsearch/elasticsearch.yml 2>/dev/null || true
    sed -i 's/#network.host:.*/network.host: 0.0.0.0/' /etc/elasticsearch/elasticsearch.yml 2>/dev/null || true
    # ⚠️ 安全加固：不再自动禁用 xpack.security（生产环境不应禁用）
    # 如需测试环境可用，请手动取消下面注释
    # sed -i 's/xpack.security.enabled: true/xpack.security.enabled: false/' /etc/elasticsearch/elasticsearch.yml 2>/dev/null || true
    grep -q "discovery.type: single-node" /etc/elasticsearch/elasticsearch.yml 2>/dev/null || \
        echo "discovery.type: single-node" >> /etc/elasticsearch/elasticsearch.yml
    ok "Elasticsearch 配置完成（端口 9900）"
}

# ═══════════════════════════════
# 10. 安装基础工具
# ═══════════════════════════════

install_tools() {
    info "安装基础工具..."
    case "$OS_ID" in
        ubuntu|debian) $PKG_INST wget curl gnupg ca-certificates lsb-release unzip zip git ;;
        centos|rhel|fedora) $PKG_INST wget curl gnupg ca-certificates unzip zip git ;;
        arch) $PKG_INST wget curl gnupg ca-certificates unzip zip git ;;
    esac
    # pip 源（国内）
    mkdir -p /root/.pip
    cat > /root/.pip/pip.conf <<'PIP'
[global]
index-url = https://mirrors.aliyun.com/pypi/simple/
trusted-host = mirrors.aliyun.com
PIP
    ok "基础工具安装完成，pip 源已切换为阿里云"
}

# ═══════════════════════════════
# 11. 构建项目
# ═══════════════════════════════

build_project() {
    info "构建项目（Maven 阿里云镜像）..."
    cd "$PROJECT_DIR"

    if [ ! -f pom.xml ]; then
        err "未找到 pom.xml，请确认项目文件完整"
        exit 1
    fi

    # 检查 Java
    java -version 2>&1 | head -1 || {
        err "Java 未安装，请检查"
        exit 1
    }

    # Maven 编译
    mvn clean package -DskipTests -q 2>&1 | tail -5 || {
        warn "Maven 构建出错，尝试重新执行..."
        mvn clean package -DskipTests 2>&1 | tail -10 || {
            err "构建失败，检查 Maven 日志"
            exit 1
        }
    }

    jar_file=$(ls target/*.jar 2>/dev/null | head -1)
    if [ -z "$jar_file" ]; then
        err "构建未生成 jar 文件"
        exit 1
    fi

    ok "构建完成: $(basename "$jar_file")"
}

# ═══════════════════════════════
# 12. 配置 systemd 服务
# ═══════════════════════════════

setup_service() {
    info "配置 systemd 服务..."

    # 创建用户
    id tgf &>/dev/null || useradd -r -s /bin/false -d /opt/tg-federal-bot tgf

    cat > /etc/systemd/system/tg-federal-bot.service <<'SERVICE'
[Unit]
Description=TG Federal Bot
After=network.target postgresql.service redis-server.service
Wants=postgresql.service redis-server.service

[Service]
Type=simple
User=tgf
Group=tgf
WorkingDirectory=/opt/tg-federal-bot
ExecStart=/usr/bin/java -jar /opt/tg-federal-bot/target/tg-federal-bot-1.0.0.jar
Restart=always
RestartSec=10
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
SERVICE

    systemctl daemon-reload
    systemctl enable tg-federal-bot
    ok "systemd 服务配置完成"
}

# ═══════════════════════════════
# 13. 配置防火墙
# ═══════════════════════════════

setup_firewall() {
    if command -v ufw &>/dev/null && ufw status | grep -q active 2>/dev/null; then
        ufw allow 8080/tcp comment 'TG Federal Bot'
        ufw allow 5432/tcp comment 'PostgreSQL'
        ufw allow 6379/tcp comment 'Redis'
        ufw allow 9900/tcp comment 'Elasticsearch'
        ok "UFW 规则已添加"
    elif command -v firewall-cmd &>/dev/null; then
        firewall-cmd --permanent --add-port=8080/tcp 2>/dev/null || true
        firewall-cmd --reload 2>/dev/null || true
        ok "firewalld 规则已添加"
    fi
}

# ═══════════════════════════════
# 14. 设置 Webhook
# ═══════════════════════════════

setup_webhook() {
    [ -z "$WEBHOOK_URL" ] && return
    info "设置 Telegram Webhook..."
    curl -s -X POST "https://api.telegram.org/bot${BOT_TOKEN}/setWebhook" \
        --socks5-hostname "${PROXY_HOST}:${PROXY_PORT}" \
        --data "url=${WEBHOOK_URL}/webhook/" 2>/dev/null || {
        # 尝试无代理
        curl -s -X POST "https://api.telegram.org/bot${BOT_TOKEN}/setWebhook" \
            --data "url=${WEBHOOK_URL}/webhook/" 2>/dev/null || true
    }
    ok "Webhook 已设置: ${WEBHOOK_URL}/webhook/"
}

# ═══════════════════════════════
# 15. 设置 Docker Compose（可选）
# ═══════════════════════════════

setup_docker_mirror() {
    if command -v docker &>/dev/null; then
        info "配置 Docker 镜像加速..."
        mkdir -p /etc/docker
        cat > /etc/docker/daemon.json <<'DOCKER'
{
    "registry-mirrors": [
        "https://docker.1ms.run",
        "https://docker.xuanyuan.me",
        "https://dockerpull.com"
    ]
}
DOCKER
        systemctl daemon-reload 2>/dev/null || true
        systemctl restart docker 2>/dev/null || true
        ok "Docker 镜像加速已配置"
    fi
}

# ═══════════════════════════════
# 主流程
# ═══════════════════════════════

main() {
    echo ""
    echo "╔════════════════════════════════════════════════════╗"
    echo "║   TG联邦智能治理机器人 - 一键部署                 ║"
    echo "║   适配国内服务器 · 所有包从国内镜像下载          ║"
    echo "╚════════════════════════════════════════════════════╝"
    echo ""

    detect_os
    interactive_config
    write_config

    echo ""
    echo "==================== 1/6 安装基础工具 ===================="
    install_tools

    echo ""
    echo "==================== 2/6 安装 Java 21 ===================="
    install_java

    echo ""
    echo "==================== 3/6 安装 Maven ===================="
    install_maven

    echo ""
    echo "==================== 4/6 安装数据库 & 缓存 ===================="
    install_postgres
    install_redis
    setup_docker_mirror

    # 本地 Ollama 安装
    if [ "$MODERATION_PROVIDER" = "local" ] && [[ "$INSTALL_OLLAMA" =~ ^[yY] ]]; then
        echo ""
        echo "========= 安装 Ollama + ${LOCAL_MODEL} ========="
        if ! command -v ollama &>/dev/null; then
            info "安装 Ollama..."
            curl -fsSL https://ollama.com/install.sh | sh 2>/dev/null || {
                warn "Ollama 官方安装失败，尝试国内镜像..."
                curl -fsSL https://mirror.ghproxy.com/https://github.com/ollama/ollama/releases/latest/download/ollama-linux-amd64.tgz -o /tmp/ollama.tgz
                tar -C /usr/local -xzf /tmp/ollama.tgz
                cat > /etc/systemd/system/ollama.service <<'SVC'
[Unit]
Description=Ollama LLM Service
[Service]
ExecStart=/usr/local/bin/ollama serve
Restart=always
[Install]
WantedBy=multi-user.target
SVC
                systemctl daemon-reload
            }
            systemctl enable ollama 2>/dev/null || true
            systemctl start ollama 2>/dev/null || true
            ok "Ollama 已安装"
        else
            ok "Ollama 已安装"
        fi

        # 等待 Ollama 就绪
        info "等待 Ollama 就绪..."
        for i in $(seq 1 30); do
            if curl -s "${LOCAL_OLLAMA_URL}/api/tags" &>/dev/null; then
                ok "Ollama 就绪"
                break
            fi
            sleep 2
        done

        # 下载模型
        if ! curl -s "${LOCAL_OLLAMA_URL}/api/tags" | grep -q "${LOCAL_MODEL}"; then
            info "下载 ${LOCAL_MODEL}（约 4-8GB，可能耗时较长）..."
            ollama pull "${LOCAL_MODEL}" 2>&1 || warn "模型下载失败，请部署后手动执行: ollama pull ${LOCAL_MODEL}"
            ok "${LOCAL_MODEL} 下载完成"
        else
            ok "${LOCAL_MODEL} 已存在"
        fi
    elif [ "$MODERATION_PROVIDER" = "local" ]; then
        info "跳过 Ollama 安装，请部署后手动安装: https://ollama.com"
    fi

    echo ""
    echo "==================== 5/6 安装 Elasticsearch ===================="
    if [[ ! "$INSTALL_ES" =~ ^[nN] ]]; then
        install_es
    else
        info "跳过 Elasticsearch 安装"
    fi

    echo ""
    echo "==================== 6/6 构建 & 启动 ===================="
    build_project
    setup_service
    setup_firewall

    # 完成
    echo ""
    echo "╔════════════════════════════════════════════════════╗"
    echo "║             部署完成！                            ║"
    echo "╠════════════════════════════════════════════════════╣"
    echo "║ 启动服务:   systemctl start tg-federal-bot        ║"
    echo "║ 查看日志:   journalctl -fu tg-federal-bot         ║"
    echo "║ 前端页面:   http://服务器IP:8080/                 ║"
    echo "║ 健康检查:   http://服务器IP:8080/actuator/health  ║"
    echo "║ 配置文件:   ${PROJECT_DIR}/src/main/resources/    ║"
    echo "║             application.properties                ║"
    echo "╠════════════════════════════════════════════════════╣"
    echo "║ 设置 Webhook（修改域名后运行）：                  ║"
    echo "║   curl -X POST https://api.telegram.org/          ║"
    echo "║     bot${BOT_TOKEN:0:15}.../setWebhook            ║"
    echo "║     -d 'url=https://你的域名/webhook/'            ║"
    echo "╚════════════════════════════════════════════════════╝"
    echo ""
}

main
