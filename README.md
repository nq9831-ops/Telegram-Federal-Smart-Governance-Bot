# TG联邦智能治理机器人(有任何疑问 请给我发送邮件 nq9831@gmail.com)项目并不是很完善 如有需求联系我


> 基于 Telegram 的联邦级群组智能治理系统
> 技术栈：Java 21 + Spring Boot 3.2.5 + PostgreSQL + Redis + Elasticsearch + DeepSeek AI

---

## 📖 目录

1. [项目概述](#1-项目概述)
2. [前置知识 & 获取配置](#2-前置知识--获取配置)
3. [部署指南](#3-部署指南)
4. [所有配置项详解](#4-所有配置项详解)
5. [Telegram Bot 命令](#5-telegram-bot-命令)
6. [Mini App REST API](#6-mini-app-rest-api)
7. [所有功能详解](#7-所有功能详解)
8. [定时任务](#8-定时任务)
9. [安全设计](#9-安全设计)
10. [数据存储](#10-数据存储)
11. [监控 & 日志](#11-监控--日志)
12. [常见问题](#12-常见问题)
13. [版本日志管理](#13-版本日志管理)
14. [升级维护](#14-升级维护)

---

## 1. 项目概述

### 1.1 这是什么

一个 Telegram Bot + Web 管理面板，用于群组内容治理和联邦信用管理。

#### 核心能力

| 维度 | 能力 |
|------|------|
| 🛡 内容审核 | AI 自动识别违规（广告/色情/赌博/诈骗/政治敏感），支持云端 DeepSeek 或本地 Ollama（数据不外出）|
| 📊 信用体系 | 0-100 分动态信用分，自动处罚/恢复/死刑/赦免，联邦联动禁言 |
| 🔄 熔断保护 | 全局 + 群组级双熔断，QPS 窗口监控，自动降级恢复 |
| 👥 白名单 | 每群最多 10 名白名单，豁免一般违规，诈骗/政治零容忍 |
| ⭐ 评分系统 | 用户互评 1-5 星，ES 持久化，防作弊 3 层防护 |
| 📋 审核工单 | 人工复核通道，审核官处理违规申述和误报纠正 |
| 🚀 邀请收录 | 邀请码系统 + 用户提交群组/机器人/代理收录 |
| 📈 排行榜 | 月度信用分/评分排行榜，Top 奖励机制 |
| 🧑‍💻 Mini App | 浏览器前端，查看评分/排行/信用/蜂斷状态 |
| 🗂 公告管理 | 群公告自动置顶/更新/移除 |
| 🔍 审计日志 | 全量操作审计，管理員 /audit 命令查询封控记录 |

#### 特色设计

- **数据主权可选**：<br/> 云端模式（DeepSeek API）或 本地模式（Ollama），一行配置切换，本地模式数据零外出
- **联邦治理**：<br/> 跨群组信用联动、动态禁言阈值、黑产防刷评分折扣
- **冷启动保护**：<br/> 新群上线 N 天内仅人工审核，积累信任后再开启自动处罚
- **违禁词预设**：<br/> 45 条内置违规模板（推广/代理/诈骗/色情/赌博），开箱即用
- **聚合通知**：<br/> 5 分钟合并异动告警，避免高频消息骚扰管理员

### 1.2 架构图

```
                              ┌────────────────────────────────────────────┐
                              │            ConcurrencyGuard               │
                              │   Semaphore(moderation=10, es=20)          │
                              │   Virtual Thread 风暴防护、API 限流         │
                              └────┬─────────────────────────┬────────────┘
                                   │                         │
  Telegram ───HTTPS───→ WebhookController ──→ Virtual Thread ─┼──→ AI 审核服务
                                   │                         │    ├─ 云端 DeepSeek API
                                   │                         │    └─ 本地 Ollama（可选）
                                   ▼                         ▼
                             Spring Boot                    ES（评分数据）
                             ├── PostgreSQL（核心数据）       └── 受 ConcurrencyGuard 限流
                             ├── Redis（缓存/限流）
                             └── Mini App 前端（端口 8080）
```

### 1.3 项目结构

```
tg-federal-bot/
├── pom.xml                     # Maven 构建
├── docker-compose.yml          # Docker 部署
├── Dockerfile                  # 容器构建
├── deploy/                     # 部署脚本
│   ├── install.sh              # 一键部署（国内镜像）
│   └── tg-federal-bot.service  # systemd 服务
│
└── src/main/java/com/tgf/bot/
    ├── FederalBotApplication.java       # 启动入口
    ├── config/                          # 3 个配置类
    ├── model/                           # 15 个数据模型
    ├── repository/                      # 2 个仓库
    ├── service/                         # 15 个业务服务
    ├── handler/                         # 7 个 Telegram 命令处理器
    ├── controller/                      # 2 个 REST 控制器
    └── scheduler/                       # 1 个定时任务
```

---

## 2. 前置知识 & 获取配置

运行前需要准备以下 4 样东西：

### 2.1 创建 Bot → 获取 Token

打开 Telegram，搜索 **@BotFather**：

```
/newbot → 起名（中文即可）→ 设置用户名（必须_bot结尾）
→ 获得 Token：1234567890:ABCdefGHIjklmNOPqrstUVWxyz-1234567
```

> 保存 Token，部署时填 `bot.token`

### 2.2 获取你的 User ID

搜索 **@userinfobot**，发 `/start`：

```
Id: 5006320370
```

> 保存这个数字，部署时填 `bot.creator`
> 多个管理员用逗号分隔：`5006320370,100000000`

### 2.3 获取 DeepSeek API Key（可选）

访问 https://platform.deepseek.com → 登录 → API Keys → 创建 Key

> 不填也能运行，系统自动降级为规则引擎

### 2.4 准备服务器

| 配置 | 最低 | 推荐 |
|------|------|------|
| 内存 | 4GB | 8GB |
| CPU | 2核 | 4核 |
| 系统 | Ubuntu 22.04 | Ubuntu 24.04 |
| 硬盘 | 20GB | 50GB SSD |

**域名（可选）：** 设置 Webhook 时需要，在阿里云/腾讯云/DNSPod 添加 A 记录解析到服务器 IP。

---

## 3. 部署指南

### 3.1 一键部署（推荐）

```bash
# 上传项目 → SSH 登录 → 执行脚本
scp -r /本地路径/tg-federal-bot root@服务器IP:/opt/
ssh root@服务器IP
cd /opt/tg-federal-bot
sudo bash deploy/install.sh
```

脚本会交互式询问以下内容（直接输入即可）：

| 询问项 | 填写说明 |
|--------|---------|
| Bot Token | 从 @BotFather 获取的 |
| Bot 用户名 | 创建 Bot 时设置的 |
| 管理员 User ID | 从 @userinfobot 获取的 |
| DeepSeek Key | 可选，回车跳过 |
| 审核模式 | 1=云端DeepSeek 2=本地Ollama（数据不外出）|
| 本地模型 | 默认 qwen2.5:7b |
| 安装Ollama？ | 选择本地后可自动安装 + 下载模型 |
| Webhook 域名 | 可选，回车跳过 |
| 使用 SOCKS5？ | 国内服务器填 Y |
| 代理地址 | 默认 127.0.0.1 |
| 代理端口 | 默认 7890 |
| 数据库密码 | 默认 postgres |
| 安装 ES？ | Y |

整个过程约 5-15 分钟（选择本地模型额外 5-15 分钟下载），自动完成：
换国内源 → 装 JDK21 → 装 Maven → 装 PostgreSQL → 装 Redis → 装 ES（可选）→ 装 Ollama（可选）→ Maven 编译 → 注册服务。

> **选择本地审核模式后**，脚本会自动安装 Ollama 并下载模型。
> 7B 模型约 4-8GB，下载耗时取决于带宽。

### 3.2 Docker Compose

```bash
cd /opt/tg-federal-bot
export BOT_TOKEN="你的Token"
export BOT_USERNAME="你的Bot用户名"
export BOT_CREATOR="5006320370"
export DEEPSEEK_API_KEY="你的Key（可选）"
docker compose up -d
```

### 3.3 配置 SOCKS5 代理（国内服务器）

```bash
# V2Ray
bash <(curl -sL https://raw.githubusercontent.com/v2fly/fhs-install-v2ray/master/install-release.sh)
sed -i 's/"port": 1080/"port": 7890/' /usr/local/etc/v2ray/config.json
systemctl enable v2ray && systemctl start v2ray

# 验证
curl --socks5-hostname 127.0.0.1:7890 https://api.telegram.org
# → {"ok":true}
```

### 3.4 设置 Webhook（让 Bot 能收群消息）

#### 有域名

```bash
apt install nginx certbot python3-certbot-nginx
# 配置反代（见下方示例）
certbot --nginx -d bot.example.com
curl -X POST "https://api.telegram.org/bot<TOKEN>/setWebhook" \
  -d "url=https://bot.example.com/webhook/"
```

Nginx 配置：

```nginx
server {
    listen 443 ssl;
    server_name bot.example.com;
    location / { proxy_pass http://127.0.0.1:8080; }
    location /webhook/ { proxy_pass http://127.0.0.1:8080; }
    location /api/ { proxy_pass http://127.0.0.1:8080; }
}
```

#### 无域名（Ngrok）

```bash
wget https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-amd64.tgz
tar xzf ngrok-v3-stable-linux-amd64.tgz -C /usr/local/bin/
ngrok http 8080  # 新开终端，记录生成地址
# 设置 Webhook
curl -X POST "https://api.telegram.org/bot<TOKEN>/setWebhook" \
  -d "url=https://xxxx-xxx.ngrok-free.app/webhook/"
```

#### 验证

```bash
curl "https://api.telegram.org/bot<TOKEN>/getWebhookInfo"
# → {"ok":true,"result":{"url":"https://.../webhook/",...}}
```

### 3.5 启动 & 验证

```bash
systemctl start tg-federal-bot
journalctl -fu tg-federal-bot  # 查看日志，看到 Started 即成功

# 验证
ss -tlnp | grep -E "8080|5432|6379|9900"
curl http://localhost:8080/actuator/health   # → {"status":"UP"}
curl http://localhost:8080/api/status
```

### 3.6 访问 Mini App 前端

浏览器打开：`http://服务器IP:8080/`

只使用前端的话，不需要配置 Webhook 和代理。前端功能全可用。

---

## 4. 所有配置项详解

配置文件：`src/main/resources/application.properties`

### 4.1 Bot 配置（必填）

```properties
bot.token=                      # Telegram Bot Token，@BotFather 获取
bot.username=                   # Bot 用户名，如 MyBot_bot
bot.creator=5006320370          # 超级管理员 UserID，多个逗号分隔
bot.webhook-url=                # Webhook 地址，可选
bot.webhook-secret=             # Webhook 验证密钥，可选
server.port=8080                # 监听端口

# SOCKS5 代理（国内服务器必须）
bot.proxy.enabled=true
bot.proxy.type=SOCKS
bot.proxy.host=127.0.0.1
bot.proxy.port=7890
```

### 4.2 数据库

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/tg_federal_bot
spring.datasource.username=postgres
spring.datasource.password=postgres          # 部署时设置的密码
spring.datasource.hikari.maximum-pool-size=20
spring.jpa.hibernate.ddl-auto=update          # 自动建表，生产用 update
spring.jpa.show-sql=false

spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.cache.redis.time-to-live=300s          # 缓存 TTL（秒）

elasticsearch.uris=http://localhost:9900      # ES 连接地址
elasticsearch.rating-index=rating_v2           # 评分索引
```

### 4.3 AI 内容审核

#### 云端 DeepSeek（默认）

```properties
deepseek.api-key=                     # API Key，不填降级规则引擎
deepseek.api-url=https://api.deepseek.com/v1/chat/completions
deepseek.model=deepseek-chat
deepseek.timeout-ms=10000             # 超时（毫秒），超时降级
deepseek.max-retries=3
deepseek.fallback-engine=true          # 熔断后使用规则引擎

# 判定阈值
deepseek.auto-execute-threshold=0.85   # ≥85% 自动执行
deepseek.human-review-threshold=0.60   # 60-85% 需人工审核，<60% 降级规则

# 审核模式（cloud 或 local）
moderation.provider=cloud              # cloud=云端API, local=本地Ollama
```

#### 本地 Ollama（数据不外出）

```properties
moderation.provider=local              # 切换到本地模式
moderation.local.api-url=http://localhost:11434/v1/chat/completions
moderation.local.model=qwen2.5:7b
```

**部署前提：**
1. 服务器安装 Ollama：`curl -fsSL https://ollama.com/install.sh | sh`
2. 下载模型：`ollama pull qwen2.5:7b`（推荐）或 `ollama pull deepseek-r1:7b`
3. 确认服务运行：`curl http://localhost:11434/api/tags`
4. 修改配置 `moderation.provider=local` 后重启 Bot

> 模型选择建议：`qwen2.5:7b` 中文理解能力强，`deepseek-r1:7b` 推理能力强。
> 7B 模型约需 4-8GB 显存，无 GPU 可用 CPU 模式（会慢 5-10 倍）。

### 4.4 信用分参数

```properties
credit.initial=100                  # 初始信用分
credit.daily-auto-increment=1       # 每日自动加分
credit.max-score=100                # 上限
# 全局禁言阈值 — 实际值由 FederalTrustService 动态计算（35-70 自适应）
# 新号 (<7天) 阈值 = 55，钻石用户 (≥80分) 阈值 = 35
credit.privilege-freeze-threshold=60 # <60 冻结高级功能

# 处罚分值
credit.punish.scam=100              # 诈骗：死刑
credit.punish.political=100         # 政治：死刑
credit.punish.porn-cross=30         # 色情跨域
credit.punish.gambling-cross=20     # 赌博跨域
credit.punish.ad=5                  # 广告（5-10 分自由裁量）
credit.punish.ad-variance=true
```

### 4.5 冷启动 + 入群验证

```properties
# 冷启动（首次部署建议开启）
system.cold-start-days=7            # 保护期天数
system.cold-start=true              # 首次部署建议 true，稳定后 false

# 入群验证
captcha.required-for-new-members=true           # 新成员是否需要验证码
captcha.new-member-restrict-minutes=10          # 新成员发言限制（分钟）
captcha.arithmetic-enabled=true                 # 启用算术验证码
captcha.failed-attempt-limit=3                  # 验证码错误次数上限
```

### 4.6 限流

```properties
rate-limit.ip.max-per-second=50     # 同 IP 每秒请求上限
rate-limit.ip.ban-seconds=3600      # IP 封禁时长（秒）
rate-limit.user.max-per-minute=10   # 单用户每分钟操作上限
rate-limit.sensitive.max-per-minute=30 # 敏感操作上限
```

### 4.6a 并发控制

```properties
# DeepSeek API 最大并发调用数（防止 API 限流/打爆）
concurrency.deepseek-max=10
# ES 最大并发写入数
concurrency.es-write-max=20
# 单个用户消息处理最大并发深度
concurrency.handle-max-depth=3
```

### 4.6b 用户提交收录

```properties
submission.cooldown-minutes=60        # 提交冷却时间（分钟）
submission.daily-limit=5              # 每日提交上限
submission.require-captcha=true        # 是否需要验证码
```

### 4.6c 邀请配置

```properties
invite.max-daily-codes=10            # 每日最多生成邀请码
invite.user-credit-reward=2           # 邀请用户奖励
invite.group-credit-reward=5          # 邀请群组奖励
invite.bot-credit-reward=3            # 邀请机器人奖励

# 每日奖励总分上限（反黑产）
# 超出上限后额外的奖励将被拦截
invite.daily-reward-cap=5
```

**作用机制：**

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `concurrency.deepseek-max` | 10 | DeepSeek API 全局信号量上限。超过时请求等待 30s 超时后返回安全兜底结果，避免 Virtual Thread 风暴打爆 API |
| `concurrency.es-write-max` | 20 | ES 评分写入并发计数器。超过时写入被静默丢弃并记日志，避免 ES 队列溢出 |
| `concurrency.handle-max-depth` | 3 | （预留）单个消息处理的最大嵌套深度，防止递归死循环 |

### 4.7 排行榜奖励

```properties
ranking.top-1-pct-reward=5          # 前 1%
ranking.top-2-5-pct-reward=3        # 2-5%
ranking.top-6-15-pct-reward=1       # 6-15%
ranking.bottom-5-pct-penalty=-5     # 后 5%
ranking.bottom-6-15-pct-penalty=-2  # 后 6-15%
```

### 4.8 用户提交收录

```properties
submission.cooldown-minutes=60       # 提交冷却时间（分钟）
submission.daily-limit=5             # 每日提交上限
submission.require-captcha=true      # 提交是否需要验证码
```

### 4.9 邀请

```properties
invite.max-daily-codes=10           # 每日最多生成邀请码
invite.user-credit-reward=2         # 邀请用户加分
invite.group-credit-reward=5        # 邀请群组加分
invite.bot-credit-reward=3          # 邀请机器人加分
```

### 4.10 存储策略

```properties
storage.message-retention-days=90     # 消息保留 90 天
storage.audit-log-retention-days=180  # 审计日志保留 180 天
```

### 4.11 功能开关

每个功能独立控制 `feature.xxx=true/false`：

| 开关 | 默认 | 说明 |
|------|------|------|
| `feature.deepseek-realtime` | true | AI 实时审核 |
| `feature.deepseek-profile` | true | AI 画像分析 |
| `feature.death-penalty-auto` | true | 死刑自动执行 |
| `feature.porn-penalty` | true | 色情处罚 |
| `feature.gambling-penalty` | true | 赌博处罚 |
| `feature.ad-penalty` | true | 广告处罚 |
| `feature.high-frequency-detection` | true | 高频检测 |
| `feature.fingerprint-dedup` | true | 指纹去重 |
| `feature.report-anti-cheat` | true | 举报反作弊 |
| `feature.captcha` | true | 验证码 |
| `feature.ip-rate-limit` | true | IP 限流 |
| `feature.ranking` | true | 排行榜 |
| `feature.invite-reward` | true | 邀请奖励 |
| `feature.review-system` | true | 审核系统 |
| `feature.broadcast` | true | 广播系统 |
| `feature.certified-advertiser` | true | 认证广告主 |
| `feature.label-audit` | true | 标签审计 |
| `feature.penalty-notification` | true | 处罚通知 |
| `feature.credit-auto-reward` | true | 信用分自动恢复 |
| `feature.aggregated-notify` | true | 聚合通知 5 分钟报告 |
| `feature.federal-trust` | true | 联邦信任动态阈值 |
| `feature.anti-fraud` | true | 黑产防刷奖励折扣 |
| `feature.group-circuit-breaker` | true | 群组级熔断 |
| `feature.link-penalty` | true | 链接处罚 — 非认证链接自动扣分 |
| `feature.forward-detection` | true | 转发检测 — 识别频道批量转发 |
| `feature.sockpuppet-detection` | true | 马甲检测 — 同IP批量注册识别 |

### 4.12 日志 & 监控

```properties
logging.level.com.tgf.bot=INFO
logging.level.org.springframework=WARN
management.endpoints.web.exposure.include=health,prometheus,info
```

---

## 5. Telegram Bot 命令

### 5.1 私聊命令

在 Telegram 中和 Bot 私聊时使用：

| 命令 | 功能 | 示例 |
|------|------|------|
| `/start` | 注册账号，开始使用 | `/start` |
| `/help` | 查看帮助 | `/help` |
| `/me` | 查看我的信用分、排名、统计 | `/me` |
| `/invite` | 获取邀请码 | `/invite` |
| `/search 关键词` | 搜索用户/群组/机器人/代理 | `/search 张三` |
| `/credit @用户` | 查看某人信用分 | `/credit @someone` |
| `/rating @用户 4` | 给用户评分（1-5星） | `/rating @someone 5` |
| `/report @用户 广告` | 举报用户 | `/report @someone 色情` |
| `/rank` | 查看排行榜 | `/rank` |
| `/captcha` | 获取验证码 | `/captcha` |
| `/profile` | 查看自己的用户画像 | `/profile` |
| `/submit` | 提交收录申请（详见下方） | `/submit group 技术交流群 123456` |
| `/submit group <名称> <ID> [链接]` | 提交群组收录 | `/submit group 开源社区 98765` |
| `/submit bot <名称> <ID> [@用户名]` | 提交机器人收录 | `/submit bot 翻译助手 54321 @transBot` |
| `/submit proxy <名称> <协议> <地址>` | 提交代理收录 | `/submit proxy 日本节点 V2Ray 1.2.3.4:443` |
| `/my_submissions` | 查看我的提交记录和审核状态 | `/my_submissions` |

### 5.2 群组命令

在 Bot 管理的群组内使用：

| 命令 | 功能 | 谁可用 |
|------|------|--------|
| `/help` | 群内帮助 | 所有人 |
| `/check` | 查看本群认证/评分状态 | 所有人 |
| `/credit` | 查看自己信用分 | 所有人 |
| `/report @用户 原因` | 举报违规 | 所有人 |
| `/invite` | 获取邀请码 | 所有人 |
| `/rank` | 排行榜 | 所有人 |
| `/whitelist` | 白名单管理菜单 | 超级管理员 |
| `/whitelist list` | 查看本群白名单 | 超级管理员 |
| `/whitelist add <用户ID>` | 添加白名单（上限10人） | 超级管理员 |
| `/whitelist remove <用户ID>` | 移除白名单 | 超级管理员 |

### 5.3 审核官命令

在群组内使用，需要审核官权限：

| 命令 | 功能 |
|------|------|
| `/review list` | 待审核工单列表 |
| `/review view 1001` | 查看工单详情 |
| `/review pass 1001` | 通过工单 |
| `/review punish 1001` | 执行处罚 |
| `/review escalate 1001` | 升级到高级审核官 |
| `/review batch 1001,1002` | 批量处理 |
| `/review stats` | 审核统计 |

### 5.4 管理命令

仅超级管理员可用（`bot.creator` 中配置的 ID）：

| 命令 | 功能 |
|------|------|
| `/admin` | 查看管理菜单 |
| `/admin global_ban @用户` | 全局封禁该用户 |
| `/admin global_unban @用户` | 解除全局封禁 |
| `/admin broadcast 消息内容` | 向所有联邦群组广播公告 |
| `/admin degrade @用户` | 降级用户权限 |
| `/admin config key=value` | 修改运行时配置（如 `feature.ad-penalty=false`） |
| `/admin emergency` | 紧急熔断，关闭所有功能 |
| `/admin rollback` | 回滚配置到默认值 |
| `/admin freeze @用户` | 冻结用户信用分（锁定不变） |
| `/admin unfreeze @用户` | 解冻信用分 |
| `/submission list` | 查看待审核收录提交 |
| `/submission approve <ID> [备注]` | 通过收录提交，自动创建实体 |
| `/submission reject <ID> [原因]` | 驳回收录提交 |
| `/circuit status <chatId>` | 查看群组熔断状态（QPS/违规数/熔断原因）|
| `/circuit recover <chatId>` | 手动恢复群组熔断 |
| `/circuit list` | 列出所有熔断中的群组 |
| `/audit` | 封控日志查询菜单 |
| `/audit user <用户ID>` | 查询某用户封控记录（信用分变更/处罚/赦免）|
| `/audit type <操作类型>` | 按操作类型查询（credit_change/punish/pardon/certify）|
| `/audit today` | 今日所有封控记录 |
| `/audit recent <数量>` | 最近 N 条记录（默认 10，最多 30）|
| `/audit operator <操作者ID>` | 按操作者查询 |

---

## 6. Mini App REST API

浏览器访问 `http://服务器IP:8080/` 即可使用前端。前端调用以下 API：

| 接口 | 方法 | 参数 | 说明 |
|------|------|------|------|
| `GET /api/rating/profile` | JSON | `?userId=xxx` | 用户档案：信用分、排行、统计 |
| `GET /api/rating/search` | JSON | `?q=xxx&type=user` | 全局搜索，type=user/group/bot/proxy |
| `GET /api/rating/detail` | JSON | `?id=xxx&type=user` | 收录详情：评分、信用记录、标签 |
| `POST /api/rating/submit` | JSON | 见下 | 提交评分（需验证码） |
| `GET /api/rating/leaderboard` | JSON | `?type=user` | 排行榜 |
| `GET /api/captcha/generate` | JSON | — | 生成验证码，返回 ID + Base64 图片 |
| `POST /api/captcha/verify` | JSON | `{captchaId, input}` | 校验验证码 |
| `GET /api/list/tickets` | JSON | `?status=pending` | 待审核工单 |
| `POST /api/ticket/review` | JSON | `{ticketId, action, reason}` | 审核工单 |
| `GET /api/status` | JSON | — | 系统状态：熔断、冷启动、统计 |
| `POST /api/submission/submit` | JSON | `targetType, title, ...` | 提交收录申请 |
| `GET /api/submission/my` | JSON | `?userId=xxx&page=1` | 查询自己的提交记录 |
| `GET /api/submission/pending` | JSON | `?page=1` | 管理员：待审核收录列表 |
| `POST /api/submission/approve` | JSON | `submissionId, reviewerId` | 通过收录申请 |
| `POST /api/submission/reject` | JSON | `submissionId, reviewerId, reason` | 驳回收录申请 |
| `GET /api/credit/history` | JSON | `?userId=xxx` | 信用分变更明细 |
| `GET /api/group/circuit/{chatId}` | JSON | — | 群组熔断状态（管理员 Token 必须） |
| `POST /api/group/recover/{chatId}` | JSON | — | 手动恢复群组熔断（管理员 Token 必须） |

**POST 请求示例：**

```json
// POST /api/rating/submit
{
  "targetId": "123456789",
  "targetType": "user",       // user/group/bot/proxy
  "score": 4,                 // 1-5
  "captchaId": "a1b2c3d4",
  "captchaInput": "X7Y2"
}

// POST /api/ticket/review
{
  "ticketId": "1001",
  "action": "pass",           // pass / reject / escalate
  "reason": "证据不足，驳回"
}
```

---

## 7. 所有功能详解

### 7.1 信用分体系

每个用户初始 **100 分**，上限 100 分，低于阈值触发逐级处罚：

| 信用分 | 状态 |
|--------|------|
| 100 | 正常 |
| < 60 | 冻结高级功能（举报/评分/邀请） |
| < 50 | 全局禁言（所有联邦群组不能发言） |
| 0 | 永久封禁 |

**加分途径：**
- 每日 04:00 自动 +1（低于 100 的自动恢复）
- 排行榜月度奖励：前 1% +5 分，前 2-5% +3 分，前 6-15% +1 分
- 邀请用户 +2 分，邀请群组 +5 分，邀请机器人 +3 分

**扣分处罚：**

| 违规 | 扣分 | 说明 |
|------|------|------|
| 诈骗 | 100 分 | 需高级审核官确认 |
| 政治敏感 | 100 分 | 需高级审核官确认 |
| 色情跨域 | 30 分 | 在非 NSFW 群组 |
| 赌博跨域 | 20 分 | 在非 GAMBLING 群组 |
| 广告 | 5-10 分 | 自由裁量 |

**24h 异动告警：** 信用分变化 ≥30 分自动通知管理员。

### 7.2 内容审核流程

```
用户发消息 → ① 模板匹配（≥80% 直接处罚，跳过 AI）
                     ↓ <80%
            ② DeepSeek AI 分析
                ≥85% → 自动执行
                60-85% → 审核官确认
                <60% → 规则引擎降级
                     ↓
            ③ 处罚执行（安全检查港豁免 → 冷启动检查 → 扣分/禁言）
```

**模板匹配优先：** 违规模板匹配度 ≥80% 直接判定，每条消息省 ~100 tokens。

**规则引擎降级：** 当 DeepSeek 不可用（API 超时/熔断/未配置 Key），自动使用规则引擎模式（模板匹配 + 逻辑规则）。

### 7.3 熔断机制

当 DeepSeek API 连续出错时自动降级：

| 状态 | 条件 | 行为 |
|------|------|------|
| 🟢 GREEN | 错误率 < 5% | 正常运行 |
| 🟡 YELLOW | 5-30% | 关闭 AI，只用规则引擎 |
| 🔴 RED | ≥ 30% | 全量禁用 AI，仅人工审核 |

YELLOW 5 分钟后自动恢复，RED 30 分钟后自动恢复。

### 7.3.1 群组级熔断（新增）

除全局熔断外，系统增加**群组级熔断**层（L1），保护中央服务器不被单个群组的消息风暴冲垮：

| 熔断层 | 触发条件 | 动作 | 恢复 |
|--------|---------|------|------|
| L1 群组级 | 单群 QPS > 5/s | 开启全员慢速模式（消息间隔 5s） | QPS 回落到 < 1.5/s 自动关闭 |
| L1 群组级（升级） | QPS > 10/s | 群组熔断，**所有消息被丢弃不处理** | 5 分钟后自动恢复 |
| L1 群组级（违规率） | 窗口 60s 内违规率 ≥ 30% | 群组熔断，跳过所有消息处理 | 5 分钟后自动恢复 |
| L2 用户级 | 单个用户跨群违规 ≥ 5 次/30 分钟 | 该用户消息被忽略（10 分钟） | 10 分钟后自动恢复 |
| L3 全局级 | DeepSeek 错误率 ≥ 5%（YELLOW）/ ≥ 30%（RED） | 规则引擎降级 / 全量禁用 AI | 5 min / 30 min |

**熔断三层次：**

```
群组级 (L1) — 单个群 QPS 暴增 / 违规率超标
   ↓ 不影响其他群
用户级 (L2) — 单个用户跨群疯狂违规
   ↓ 不影响其他用户
全局级 (L3) — DeepSeek API 不可用 / 系统过载
```

**熔断后如何恢复：**
- **自动：** 定时任务每 30 分钟检查过期熔断群组并恢复
- **手动：** 管理员通过 `/circuit recover <chatId>` 或 Mini App API 手动恢复
- **查询状态：** `/circuit status <chatId>` 或 `GET /api/group/circuit/{chatId}`

**管理员命令：**
```
/circuit status <chatId>      # 查看群组熔断状态
/circuit recover <chatId>     # 手动恢复群组
/circuit list                 # 列出所有熔断群组
```

### 7.4 安全港豁免

| 群组标签 | 豁免内容 |
|---------|---------|
| `NSFW` | 色情内容处罚 |
| `GAMBLING` | 赌博内容处罚 |

滥用安全港（标签与实际内容不符）→ 取消豁免并处罚群主。

### 7.5 死刑确认

诈骗/政治违规（扣 100 分归零）需要：AI 判定 → 创建工单 → 高级审核官手动确认 → 执行处罚。不可自动执行。

### 7.6 评分系统

用户/群组/机器人/代理均支持 1-5 星评分。

**防作弊 3 层：**
1. **频率限制：** 同用户 30 秒 1 次，24h 50 次（已有评分允许改分，跳过频率限制）
2. **IP 限流：** 同 IP 每秒 10 次，超限封禁 1h
3. **异常检测：** 同设备 24h >5 次触发告警工单

**验证码：** 评分前必须通过图形验证码（Kaptcha 4 位字符 + 阴影干扰，Redis 5 分钟过期，一次性校验）。

**评分流程：** 用户发起评分 → preCheck（频率/重复校验） → captcha 校验 → rate（写入 ES rating_v2 索引） → 评分成功奖励 2 信用分。

**评分奖励：** 每次评分成功给评分人 +2 信用分，每日评分不限次但有频率限制。

### 7.7 冷启动保护

部署**前 7 天**所有自动处罚转为待确认工单，不实际执行。到期后批量执行 pending 工单。给管理员留出调整规则的时间。

### 7.8 邀请码系统

每个用户有 8 位邀请码（UUID 截断），可邀请用户/群组/机器人加入联邦，邀请成功后双方获得信用分奖励。每日最多生成 10 个。

### 7.9 审核工单系统

举报和 AI 判定的违规自动生成工单；审核官通过 `/review` 命令或前端查看处理；支持通过/处罚/升级三种操作；SLA 超时自动升级。

### 7.10 标签审计

定时检查联邦群组的标签（NSFW/GAMBLING 等）是否与实际内容一致，不一致的取消安全港豁免并处罚。

### 7.11 认证广告主

用户可申请认证为广告主，认证后可在联邦群组发广告（每日 3-6 条配额），未认证用户发广告扣信用分。

### 7.12 用户自行提交收录

用户可以提交群组/机器人/代理信息，申请纳入联邦索引。审核通过后自动创建收录实体。

**提交方式：**
- **Telegram 命令：** `/submit group 名称 ID` / `/submit bot 名称 ID` / `/submit proxy 名称 协议 地址`
- **Mini App 前端：** 通过 POST `/api/submission/submit` 提交

**流程：**
1. 用户填写目标信息（名称、描述、联系方式等）
2. 系统验证：信用分 ≥ 30、冷却时间 60 分钟、每日上限 5 次、防重复
3. 生成 SubmissionEntity，状态 PENDING
4. 自动创建关联审核工单（Ticket）
5. 管理员审核通过 → 自动创建对应的 GroupEntity / BotEntity / ProxyEntity
6. 管理员驳回 → 记录驳回原因

**限制：**
- 信用分 ≥ 30 才能提交
- 每次提交间隔 60 分钟
- 每日上限 5 次
- 同类型同目标不能重复提交
- 可选验证码校验

**收录的更新：** 用户提交的收录初始环境信用分/机器人信用分/代理信用分均为 100，之后通过评分系统和行为评估动态调整。

#### 7.14 信用分异动告警

当用户的信用分在 24 小时内变化超过 ±30 分（排除系统自动奖励）时，自动创建告警工单通知管理员。

触发条件：
- 处罚/举报等人工干预导致的信用分骤降
- 异常刷分行为（如短时间内大量评分/邀请）
- 任何非自动奖励的大幅波动（变化绝对值 ≥ 30）

### 7.15 群组跳蚤检测

统计用户在 7 天内进出群组的次数。超过 5 次则标记为「群组跳蚤」并扣 10 分，防范刷分/马甲行为。

### 7.16 多人踢出告警

用户 30 天内被 3 个以上群组踢出时，自动创建告警工单，提示管理员关注该用户。

---

### 7.17 聚合通知（新增）

系统每 **5 分钟** 自动合并所有异动/告警事件，生成汇总报告发送管理员。

**聚合规则：**

| 事件类型 | 聚合方式 | 推送条件 |
|---------|---------|---------|
| 信用分异动（≥±15 分） | 按用户聚合，最多显示 8 条 | 每次触发 |
| 群组熔断 | 按群组聚合，含 QPS/违规率 | 熔断触发 |
| 死刑执行 | 单独记录，含用户 ID 和原因 | 每次执行 |
| 工单升级 | 按工单聚合，含超时时间 | 升级触发 |

**报告示例：**

```
📊 系统异动报告 (14:30)
窗口: 约 5 分钟 | 事件数: 18

信用分异动 (14 条)
  · 用户 123456 信用分异动: 85 → 65 (-20) ...
  · 用户 789012 信用分异动: 30 → 0 ...
  ... 还有 6 条

死刑执行 (2 条)
  · 用户 345678 被执行死刑: 确凿诈骗
  · 用户 901234 被执行死刑: 政治违规
---
📌 关键数字
  信用分异动: 14 次
  群组熔断: 2 次
  死刑执行: 2 次
```

`AggregatedNotifier.java` 驱动。

### 7.18 联邦信任机制（新增）

#### 动态全局禁言阈值

不再使用固定的 **<50 分** 禁言，而是根据用户信任等级计算实际阈值：

| 因素 | 影响 |
|------|------|
| 信用分 ≥ 80（钻石信誉） | 阈值降 15 分（35 分才禁言）|
| 信用分 ≥ 60（黄金信誉） | 阈值降 8 分（42 分才禁言）|
| 信用分 ≥ 40（白银信誉） | 阈值降 3 分（47 分才禁言）|
| 账号注册 ≥ 30 天 | 阈值降 5 分 |
| 账号注册 < 7 天（新号） | 阈值升 5 分（更易被禁言）|
| 高级审核官 | 阈值降 10 分 |
| 近 7 天违规 ≥ 5 次 | 阈值升 10 分 |
| 种子用户（管理员保护） | **永不禁言** |

**效果对比：**
- **老用户：** 信用分 35 分才会被禁言（更宽容）
- **新号：** 信用分 55 分就会被禁言（更严格）
- **核心用户：** 除非低到 35-40 分，否则照常发言

#### 黑产防刷

| 攻击方式 | 防御措施 |
|---------|---------|
| 批量注册小号 → 每天自动 +1 养号 | 注册 < 7 天的账号**不享受**每日自动加分 |
| 互相邀请 → 刷邀请奖励 | 邀请奖励受每日上限限制（≤5 分），邀请者不活跃时折扣 30% |
| 同 IP 注册多号 | 同 IP 7 天内注册 ≥ 3 个账号 → 奖励折扣 50% |
| 挂机养号对冲扣分 | 超出每日奖励上限后加分被拦截 |

`FederalTrustService.java` 驱动。

---

## 8. 定时任务

`BotScheduler.java` 驱动，所有任务自动执行：

| 时间 | 任务 | 实现说明 |
|------|------|---------|
| 每日 02:00 | 用户画像分析 | 查询当日发言 ≥ 10 条的用户，打包最近 20 条跨群组消息调用 DeepSeek 分析，更新风险等级 |
| 每日 03:00 | 信用分自动恢复 | SQL 批量：注册 ≥ 7 天的用户每日 +1（上限 100），拦截新号批量养号 |
| 每日 04:00 | 链接巡检 | 标记成员数为 0 的群组、30 天未更新的群组、在线率 < 10% 的代理为不活跃 |
| 每周日 04:00 | 标签合规审计 | 采样 NSFW/GAMBLING 标签群组最近 50 条消息，对应内容占比 < 60% 阶梯扣分：首次 -10 环评分、二次 -20、三次撤销标签并禁止重申请 |
| 每周一 00:00 | 排行榜结算 | 按信用分排名：前 1% +5、2-5% +3、6-15% +1、后 5% -5、6-15% -2 |
| 每 30 分钟 | 熔断状态检查 | YELLOW 时尝试恢复 DeepSeek；同时恢复过期熔断群组 |
| 每 30 分钟 | 熔断状态检查 | YELLOW 状态时自动尝试恢复 DeepSeek API 连接；同时自动恢复过期熔断群组 |
| 每 5 分钟 | 聚合通知推送 | AggregatedNotifier.flush() 合并窗口内异动事件发送 |

**定时任务并发保护：** 所有定时任务通过 `@Scheduled` 单线程池（`spring.task.scheduling.pool.size=1` 默认）执行，不会出现同一任务重叠执行。排行榜/画像分析等重量级任务如果执行超时，下次调度会被跳过直到前次完成。

**高并发运行时 DeepSeek API 调用排队：** 消息分类等实时任务在高峰期会通过 `ConcurrencyGuard` 信号量排队，等待超过 30s 的请求直接返回安全兜底结果，不打爆 API。

**关键执行日志：** 所有定时任务执行结果写入 `application.log`，可通过 `journalctl -fu tg-federal-bot` 查看。

**命令行手动触发：** 定时任务暂不支持手动触发，重启服务后按 cron 自动执行首次调度。

---

## 9. 安全设计

### 9.1 防评分作弊（3层）

| 层级 | 方式 | 实现 |
|------|------|------|
| 1 | 用户频次 | 30 秒 1 次，24h 50 次 |
| 2 | IP 限流 | 每秒 10 次，超限封禁 1h |
| 3 | 异常检测 | 设备指纹 24h >5 次触发告警 |

### 9.2 验证码

- Kaptcha 4 位随机字符 + 阴影干扰
- Redis 存储 5 分钟过期
- 一次性校验，用完即删

### 9.2.1 入群验证

新成员加入时开启算术验证码，10 分钟内限制发言。

**流程：**
1. 新成员入群 → 自动私信发送算术验证码（如 3+5=?）
2. 用户 5 分钟内回复正确 → 解除限制
3. 用户 5 分钟内 3 次错误/超时 → 踢出群组
4. 10 分钟内即使通过验证也进入慢速模式（消息间隔 30 秒）

**配置：**

```properties
captcha.required-for-new-members=true
captcha.new-member-restrict-minutes=10
captcha.arithmetic-enabled=true
captcha.failed-attempt-limit=3
```

### 9.2.2 基础反垃圾

系统内置多层反垃圾机制：

| 防护层 | 说明 |
|--------|------|
| 链接检测 | 非认证用户发链接自动扣分 + 禁言 |
| 转发检测 | 批量转发频道消息 → 标记为马甲行为 |
| 违禁词模板 | 50+ 预设违禁词模板（推广/代理/诈骗/色情/赌博）|
| 高频检测 | 同用户短时间内大量消息 → 熔断 |
| 马甲检测 | 同 IP 批量注册 → 限制奖励权益 |

> 违禁词模板存储在 `violation_template` 表，
> 部署时 `init.sql` 自动插入 45 条预设模板。
> 管理员可在后台通过面板或数据库继续调整。

### 9.3 安全港豁免

NSFW 豁免色情、GAMBLING 豁免赌博。滥用取消豁免。

### 9.4 死刑确认

诈骗/政治必须人工确认，不得自动执行。

### 9.5 功能开关

每个功能可独立开关，管理员可通过 `/admin config` 命令随时调整。

### 9.6 并发保护（ConcurrencyGuard）

高并发场景下系统通过 `ConcurrencyGuard` 信号量机制保护后端资源：

| 保护点 | 机制 | 上限 | 超限行为 |
|--------|------|------|----------|
| DeepSeek API 调用 | `Semaphore`（公平） | `concurrency.deepseek-max=10` | 等待 30s 超时后返回安全兜底结果 |
| ES 评分写入 | `AtomicInteger` 计数器 | `concurrency.es-write-max=20` | 写入被静默丢弃，记录 `WARN` 日志 |
| Webhook Virtual Thread | 每请求 1 VT（无限，但 API 被限流） | — | VT 被打满前 DeepSeek 信号量先限流 |

**设计原则：**
- **永不阻塞 Webhook HTTP 响应：** 所有耗时操作异步化（Virtual Thread）
- **降级优先：** API 限流 → 返回安全兜底，不打爆下游
- **有损但不丢：** ES 写入丢弃时记日志可溯源

### 9.7 聚合通知防骚扰

系统每 5 分钟通过 `AggregatedNotifier` 合并所有异动告警，避免高频通知骚扰管理员。

**聚合事件类型：**
- 信用分异动（≥±15 分）
- 群组熔断事件
- 死刑执行
- 工单超时升级

**报告示例：**

```
📊 系统异动报告 (14:30)
窗口: 约 5 分钟 | 事件数: 18

信用分异动 (14 条)
  · 用户 123456 信用分异动: 85 → 65 ...
  ... 还有 6 条
---
📌 关键数字
  信用分异动: 14 次
  死刑执行: 2 次
```

### 9.8 联邦信任与黑产防刷

**动态禁言阈值（替代固定 50 分阈值）：**

| 用户类型 | 信用分禁言阈值 |
|---------|--------------|
| 新号（< 7 天） | 55 分（更严格）|
| 标准用户 | 50 分 |
| 老用户（≥ 30 天） | 45 分 |
| 高级审核官 | 40 分 |
| 钻石信誉用户（≥ 80 分） | 35 分 |

**黑产防刷机制：**
- 注册 < 7 天不享受每日自动加分
- 每日奖励总分上限 5 分
- 同 IP 7 天注册 ≥ 3 个账号 → 奖励折扣 50%
- 邀请者不活跃 → 奖励折扣 30%

---

## 10. 数据存储

| 数据 | 引擎 | 保留期 |
|------|------|--------|
| 用户 | PostgreSQL `users` | 永久 |
| 群组 | PostgreSQL `groups` | 永久 |
| 机器人 | PostgreSQL `bots` | 永久 |
| 代理 | PostgreSQL `proxies` | 永久 |
| 消息记录 | PostgreSQL `messages` | 90 天 |
| 审计日志 | PostgreSQL `audit_logs` | 180 天 |
| 工单 | PostgreSQL `tickets` | 1 年 |
| 收录提交 | PostgreSQL `submissions` | 1 年 |
| 退群记录 | PostgreSQL `user_leaves` | 90 天 |
| 违规模板 | PostgreSQL `violation_templates` | 永久 |
| 评分规则版本 | PostgreSQL `rating_rules` | 永久 |
| 版本日志 | PostgreSQL `version_logs` | 永久 |
| 系统配置 | PostgreSQL `system_configs` | 永久 |
| 群组管理员 | PostgreSQL `group_admins` | 永久 |
| 反馈记录 | PostgreSQL `false_positive_feedback` | 永久 |
| 评分记录 | Elasticsearch `rating_v2` | 永久 |
| 验证码 | Redis | 5 分钟过期 |
| 限流计数 | Redis | 窗口过期 |
| 排行榜 | Redis SortedSet | 月度重置 |

---

## 11. 监控 & 日志

```bash
# 健康检查
curl http://localhost:8080/actuator/health     # → UP/DOWN

# Metrics（Prometheus 格式）
curl http://localhost:8080/actuator/prometheus

# 实时日志
journalctl -fu tg-federal-bot

# 查看端口
ss -tlnp | grep -E "8080|5432|6379|9900"
```

### 9.9 群组白名单

每个群组可设置最多 10 名白名单用户。白名单用户在该群中不受一般违规处罚（广告/色情/赌博 豁免），但
**诈骗和政治内容零容忍**——即使白名单用户发送诈骗/政治内容也会被处罚。

**管理命令：**

| 命令 | 功能 |
|------|------|
| `/whitelist list` | 查看本群白名单 |
| `/whitelist add <用户ID>` | 添加白名单（上限 10 人） |
| `/whitelist remove <用户ID>` | 移除白名单 |

**实现逻辑：**

```
handleMessage()
  ├─ 白名单检查 → isWhitelisted?
  │   ├─ 是 → 检查违规是否为死刑类（诈骗/政治）
  │   │   ├─ 是死刑 → 正常处罚，零容忍
  │   │   └─ 非死刑 → 放行，不扣分不处罚
  │   └─ 否 → 正常审核流程
  └─ 进入模板匹配 / DeepSeek 分类
```

---

## 12. 常见问题

### Bot 不回复消息

```bash
curl "https://api.telegram.org/bot<TOKEN>/getWebhookInfo"
# 检查 Webhook url 是否正确，pending_updates 是否增长
journalctl -fu tg-federal-bot  # 看服务日志
```

### 国内服务器无法访问

```bash
lsof -i :7890                    # 检查代理是否运行
curl --socks5 127.0.0.1:7890 https://api.telegram.org  # 测试连通
```

### 数据库连接失败

```bash
systemctl status postgresql
su - postgres -c "psql -l" | grep tg_federal_bot
su - postgres -c "psql -c \"ALTER USER postgres PASSWORD 'postgres';\""
```

### ES 启动失败（内存不足）

```bash
# 修改 /etc/elasticsearch/jvm.options
# -Xms1g → -Xms512m, -Xmx1g → -Xmx512m
systemctl restart elasticsearch
```

### 如何重新配置？

```bash
vim /opt/tg-federal-bot/src/main/resources/application.properties
systemctl restart tg-federal-bot
```

---

## 13. 版本日志管理

系统自动记录每次核心配置、功能开关变化到 `version_logs` 表。每次 `@Scheduled` 定时任务执行后也会记录执行摘要。

管理员可通过 `/admin version` 查看版本日志和系统变更历史。

---

## 14. 升级维护

```bash
cd /opt/tg-federal-bot
git pull
mvn clean package -DskipTests
systemctl restart tg-federal-bot
```

---

## 安全说明

### 敏感信息保护

| 项目 | 现状 | 建议 |
|------|------|------|
| `application.properties` | ❌ 之前被误提交到 Git（已清除） | ✅ 已加入 `.gitignore`，禁止再次提交 |
| Bot Token / 密码 | ❌ 在代码仓库中有历史记录 | ✅ 已从 Git 历史中移除（`git rm --cached`） |
| 生产部署 | ⚠️ 部署脚本会将 Token 写入源码目录 | ✅ `deploy/install.sh` 同时写入 `/etc/tg-federal-bot/` |
| Docker 端口暴露 | ⚠️ 开发 `docker-compose.yml` 映射 5432/6379/9900 | ✅ 新增 `docker-compose.prod.yml`，无公网端口映射 |
| Elasticsearch 安全 | ⚠️ 开发环境关闭 xpack 认证 | ✅ 生产 `docker-compose.prod.yml` 启用安全认证 |
| Docker 运行用户 | ⚠️ 旧 Dockerfile 以 root 运行 | ✅ 新 Dockerfile 使用非 root `tgf` 用户 |

> ⚠️ **如果你 fork/clone 了这个仓库，强烈建议：**
> 1. `git filter-branch` 清除历史中的敏感信息（如有）
> 2. 立即更换 Bot Token 和所有密码
> 3. 生产环境使用 `docker-compose.prod.yml` + 环境变量注入

### 如何报告漏洞

请通过 GitHub Issues 提交，或联系仓库所有者。

---

## 贡献指南

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feature/xxx`
3. 提交改动：`git commit -m "feat: 新功能"`
4. 推送到分支：`git push origin feature/xxx`
5. 创建 Pull Request

**编码规范：**
- Java 21，遵循 Spring Boot 3.2 最佳实践
- 类和方法添加中文 Javadoc
- 所有 public 方法需要行内注释说明
- 提交前运行 `mvn compile` 确保无编译错误

---

## 🔗 参考链接

| 资源 | 地址 |
|------|------|
| @BotFather | https://t.me/botfather |
| @userinfobot | https://t.me/userinfobot |
| DeepSeek | https://platform.deepseek.com |
| Ngrok | https://ngrok.com/download |
