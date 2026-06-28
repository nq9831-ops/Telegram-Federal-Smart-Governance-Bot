# 🤖 TG联邦智能治理机器人

> 跨群组联邦信用治理体系 · AI 智能审核 · 多级熔断防护 · 实时联防  
> **技术栈：** Java 21 + Spring Boot 3.2.5 + PostgreSQL + Redis + Elasticsearch + DeepSeek AI  
> **联系方式：** nq9831@gmail.com

[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker)](docker-compose.yml)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk)](pom.xml)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.5-6DB33F?logo=spring)](pom.xml)
[![License](https://img.shields.io/badge/License-MIT-yellow)](#)

---

## 📖 目录

1. [项目概述](#1-项目概述)
2. [核心能力](#2-核心能力)
3. [快速部署](#3-快速部署)
4. [前置知识 & 获取配置](#4-前置知识--获取配置)
5. [所有配置项详解](#5-所有配置项详解)
6. [Telegram Bot 命令](#6-telegram-bot-命令)
7. [Mini App REST API](#7-mini-app-rest-api)
8. [所有功能详解](#8-所有功能详解)
9. [定时任务](#9-定时任务)
10. [安全设计](#10-安全设计)
11. [数据存储](#11-数据存储)
12. [监控 & 日志](#12-监控--日志)
13. [常见问题](#13-常见问题)
14. [升级维护](#14-升级维护)

---

## 1. 项目概述

### 1.1 这是什么

一个 **Telegram Bot + Web 管理面板**，面向 **Telegram 多群组生态** 的去中心化智能治理系统。  
它打破了传统 Bot 只能单群管理的局限，通过**联邦信用分 + AI 语义审核 + 多级熔断**的架构，让数万个群组共享一套信用体系，实现 **「一处违规，全域联防」**。

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
├── pom.xml                         # Maven 构建
├── Dockerfile                      # Docker 多阶段构建
├── docker-compose.yml              # Docker Compose 一键部署
├── .env.example                    # Docker 环境变量模板（复制为 .env）
├── .gitignore
├── README.md                       # 本文档
│
├── deploy/
│   ├── install.sh                  # 一键离线部署脚本
│   ├── init.sql                    # 违规模板预设数据
│   └── tg-federal-bot.service      # systemd 服务单元
│
├── src/main/java/com/tgf/bot/
│   ├── FederalBotApplication.java  # 启动入口
│   ├── config/                     # 9 个配置类（CORS/AOP/Redisson/ES/Health等）
│   ├── model/                      # 16 个数据模型（15 JPA + 1 ES）
│   ├── repository/                 # 3 个 JPA 仓库
│   ├── service/                    # 19 个业务服务
│   ├── handler/                    # 6 个 Telegram 命令处理器
│   ├── controller/                 # 2 个 REST 控制器
│   └── scheduler/                  # 1 个定时调度器（5 个定时任务）
│
└── src/main/resources/
    ├── application.properties      # 应用配置（环境变量占位符）
    └── static/index.html           # Mini App 前端页面
```

### 1.4 数据模型（15 张表）

| 分类 | 实体 | 说明 |
|------|------|------|
| 用户体系 | User / Group / Bot / Proxy | 联邦身份主体 |
| 安全体系 | ViolationTemplate / Ticket / AuditLog | 违规模板 + 工单 + 审计 |
| 信用体系 | RatingRecord (ES) / RatingRule | 评分数据 + 规则版本 |
| 协同体系 | GroupAdmin / UserLeave / Submission | 群管 + 离群 + 收录申请 |
| 运营体系 | SystemConfig / VersionLog / FalsePositiveFeedback | 配置 + 版本 + 误报反馈 |

---

## 2. 核心能力

### 🧠 AI 智能审核
- 消息实时语义分析，自动判定违规等级（广告/色情/赌博/诈骗/政治敏感等）
- 80+ 违规模板预设，高频违规秒级匹配
- AI 分析异步处理，不影响消息主流程
- ✅ **云端模式**（DeepSeek API） / ✅ **本地模式**（Ollama，数据零外出）

### ⭐ 联邦信用分体系
- **信用分 0-100**，跨群组统一，一处违规全域联动
- **动态禁言阈值**（35-70 自适应），不再一刀切
- **种子用户**永不自动禁言
- 信用分异动 ≥±15 分实时通知管理员

### 🛡️ 四层安全防御
```
请求层  → ConcurrencyGuard 信号量限流（10 deepseek / 20 es）
群组层  → 群组级熔断（QPS / 违规率双指标滑动窗口）
用户层  → 跨群违规用户 10 分钟静默封禁
全局层  → API 熔断 → 纯规则引擎降级
```

### 📋 三级审核体系
```
提交 → 模板匹配命中 → 自动执行
    ↕ 未命中 → AI 分析 → 审核官复核 → 执行/驳回
    ↕ 严重违规 → 上升至二级/三级人工审核
```

### 🏆 联邦排行榜
- 用户信誉榜 / 群组环境榜 / 机器人服务榜 / 代理稳定榜
- Top 20 展示，每日自动结算更新
- 信用分自动恢复 + 排行奖励机制

### 👮 审核工单系统
- 优先级 ⚪🟡🔴 三级，SLA 倒计时
- 批量处理、升级、误报纠正
- **聚合通知**：5 分钟窗口合并异动事件

### 🚀 更多特性
| 特性 | 说明 |
|------|------|
| 邀请码系统 | 用户邀请奖励 + 黑产防刷评分折扣 |
| 冷启动保护 | 新群 N 天内仅人工审核 |
| 用户提交收录 | 群组/机器人/代理提交审核收录 |
| 公告管理 | 群公告自动置顶/更新/移除 |
| 安全港标签 | NSFW/GAMBLING 标签豁免管控 |
| 高并发审计 | 虚拟线程风暴防护，Semaphore 限流 |
| 熔断自动恢复 | L1 群组 5 分钟 → L2 用户 10 分钟 → L3 全局 5-30 分钟 |

---

## 3. 快速部署

### 3.1 Docker 一键部署 🐳（推荐）

```bash
git clone https://github.com/nq9831-ops/Telegram-Federal-Smart-Governance-Bot.git
cd Telegram-Federal-Smart-Governance-Bot

# 创建 .env 文件
cat > .env << EOF
BOT_TOKEN=你的TelegramBotToken
BOT_USERNAME=你的Bot用户名
BOT_CREATOR=你的UserID
DEEPSEEK_API_KEY=你的DeepSeekKey(可选)
EOF

# 启动全部服务
docker compose up -d

# 验证
curl http://localhost:8080/
curl http://localhost:8080/api/system/status
```

等待约 1-2 分钟（首次拉镜像可能需要更久），访问 `http://localhost:8080` 即可看到管理面板。

### 3.2 传统部署

#### 3.2.1 一键脚本部署

```bash
# 如果上述 curl|bash 失败（网络限制），可以先在有 GitHub 账号的机器上 clone：
git clone git@github.com:nq9831-ops/Telegram-Federal-Smart-Governance-Bot.git
cd Telegram-Federal-Smart-Governance-Bot
# 然后打包 scp 到服务器：
tar czf bot.tar.gz .
scp bot.tar.gz root@服务器IP:/opt/
ssh root@服务器IP
cd /opt && tar xzf bot.tar.gz && cd Telegram-Federal-Smart-Governance-Bot
sudo bash deploy/install.sh
```

> **💡 国内服务器推荐**：项目内置了 [install.sh](deploy/install.sh) 终极部署脚本，自动完成以下工作：
> - 自动安装 **Clash Meta (kcla)** 本地 SOCKS5 代理，国内 VPS 直通 Telegram API
> - 所有安装包从国内镜像（阿里云/华为云）下载，无需翻墙
> - 架构自适应：Ubuntu/Debian/CentOS 全支持，x86_64/ARM64 自动适配
> - PostgreSQL 密码强校验（8位+禁止 postgres），Docker JSON 合并模式
> - 凭据分离：`application.properties`（600 权限）+ `application-public.properties`（644 权限）
> 
> ```bash
> # 国内服务器一键部署（需准备 Clash 订阅链接）
> sudo bash -c "$(curl -fsSL https://raw.githubusercontent.com/nq9831-ops/Telegram-Federal-Smart-Governance-Bot/main/deploy/install.sh)"
> ```
> 
> 部署前准备：**Clash/V2ray 订阅链接**（必填）、Bot Token、DeepSeek API Key（可选）、高强度 PG 密码
脚本会交互式询问配置项（Bot Token、DeepSeek Key、审核模式等），全过程 5-15 分钟自动完成。

#### 3.2.2 环境要求

| 配置 | 最低 | 推荐 |
|------|------|------|
| 内存 | 4GB | 8GB |
| CPU | 2核 | 4核 |
| 系统 | Ubuntu 22.04 | Ubuntu 24.04 |
| 硬盘 | 20GB | 50GB SSD |

### 3.3 设置 Webhook

#### 有域名（推荐）
```bash
apt install nginx certbot python3-certbot-nginx

# Nginx 配置
cat > /etc/nginx/sites-available/bot << 'EOF'
server {
    listen 443 ssl;
    server_name bot.example.com;
    location / { proxy_pass http://127.0.0.1:8080; }
    location /webhook/ { proxy_pass http://127.0.0.1:8080; }
    location /api/ { proxy_pass http://127.0.0.1:8080; }
}
EOF

certbot --nginx -d bot.example.com

# 设置 Webhook
curl -X POST "https://api.telegram.org/bot<TOKEN>/setWebhook" \
  -d "url=https://bot.example.com/webhook/"
```

#### 无域名（Ngrok）
```bash
ngrok http 8080  # 获取 https://xxxx-xxx.ngrok-free.app
curl -X POST "https://api.telegram.org/bot<TOKEN>/setWebhook" \
  -d "url=https://xxxx-xxx.ngrok-free.app/webhook/"
```

#### 验证
```bash
curl "https://api.telegram.org/bot<TOKEN>/getWebhookInfo"
# → {"ok":true,"result":{"url":"https://.../webhook/",...}}
```

### 3.4 验证部署

```bash
curl http://localhost:8080/actuator/health   # → {"status":"UP"}
curl http://localhost:8080/api/system/status  # → {"version":"v1.0.0",...}
```

---

## 4. 前置知识 & 获取配置

### 4.1 创建 Bot → 获取 Token
打开 Telegram 搜索 **@BotFather**，发送 `/newbot`，获得 Token。

### 4.2 获取你的 User ID
搜索 **@userinfobot**，发 `/start`。

### 4.3 获取 DeepSeek API Key（可选）
访问 [platform.deepseek.com](https://platform.deepseek.com) → API Keys → 创建。
> 不填也能运行，系统自动降级为规则引擎。

---

## 5. 所有配置项详解

配置文件：`src/main/resources/application.properties`  
**所有地址使用环境变量占位符**，Docker 环境通过环境变量覆盖，本地开发使用默认值。

### 5.1 应用配置
```properties
server.port=8080

# PostgreSQL（JPA 自动建表）
spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/tg_federal_bot}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME:postgres}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:postgres}
spring.jpa.hibernate.ddl-auto=update

# Redis
spring.data.redis.host=${SPRING_DATA_REDIS_HOST:localhost}
spring.data.redis.port=6379

# Elasticsearch
elasticsearch.uris=${ELASTICSEARCH_URIS:http://localhost:9900}
elasticsearch.rating-index=rating_v2
```

### 5.2 Bot 配置
```properties
bot.token=${BOT_TOKEN:test}
bot.username=${BOT_USERNAME:tg_federal_bot}
bot.creator=${BOT_CREATOR:5006320370}        # 管理员 UserID
bot.proxy-enabled=${BOT_PROXY_ENABLED:false}  # 代理模式
```

### 5.3 AI 内容审核
```properties
# 云端模式（默认）
deepseek.api-key=${DEEPSEEK_API_KEY:}
deepseek.api-url=https://api.deepseek.com/v1/chat/completions
deepseek.model=deepseek-chat
moderation.provider=cloud

# 本地模式（切换为 local）
# moderation.provider=local
# moderation.local.api-url=http://localhost:11434/v1/chat/completions
# moderation.local.model=qwen2.5:7b
```

完整配置项清单请参见 [src/main/resources/application.properties](src/main/resources/application.properties)。

---

## 6. Telegram Bot 命令

### 私聊命令

| 命令 | 功能 |
|------|------|
| `/start` | 注册账号 |
| `/me` | 查看我的信用分、排名 |
| `/invite` | 获取邀请码 |
| `/search 关键词` | 搜索用户/群组/机器人/代理 |
| `/credit @用户` | 查看某人信用分 |
| `/rating @用户 1-5` | 给用户评分 |
| `/report @用户 原因` | 举报用户 |
| `/rank` | 查看排行榜 |
| `/profile` | 查看自己的用户画像 |
| `/submit group <名称> <ID>` | 提交群组收录 |
| `/submit bot <名称> <ID>` | 提交机器人收录 |
| `/submit proxy <名称> <协议> <地址>` | 提交代理收录 |

### 群组命令

| 命令 | 功能 | 谁可用 |
|------|------|--------|
| `/check` | 查看本群状态 | 所有人 |
| `/credit` | 查看自己信用分 | 所有人 |
| `/report @用户 原因` | 举报 | 所有人 |
| `/whitelist add/remove/list` | 白名单管理 | 超级管理员 |

### 审核官命令

| 命令 | 功能 |
|------|------|
| `/review list` | 待审核工单列表 |
| `/review pass/punish/escalate <ID>` | 处理工单 |

### 管理命令（超级管理员）

| 命令 | 功能 |
|------|------|
| `/admin global_ban/unban @用户` | 全局封禁/解封 |
| `/admin broadcast 消息` | 向所有联邦群组广播 |
| `/admin config key=value` | 修改运行时配置 |
| `/admin freeze/unfreeze @用户` | 冻结/解冻信用分 |
| `/circuit status/recover/list` | 群组熔断管理 |
| `/audit user/type/today/recent` | 审计日志查询 |

---

## 7. Mini App REST API

浏览器前端（`http://localhost:8080`）提供的 REST API：

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/users/{id}` | GET | 获取用户信用分/等级 |
| `/api/search` | GET | 搜索（keyword/type/country/sort_by/page） |
| `/api/detail/{uuid}` | GET | 实体详情 |
| `/api/ranking/{type}` | GET | 排行榜（user/group/bot/proxy） |
| `/api/rating/{uuid}` | POST | 评分（参数：user_id, score） |
| `/api/system/status` | GET | 系统状态（冷启动/断路器/统计） |
| `/api/review/list` | GET | 审核工单列表 |
| `/api/review/{action}/{id}` | POST | 处理工单（pass/punish/escalate） |
| `/api/captcha` | GET | 获取验证码 |

---

## 8. 所有功能详解

### 8.1 内容审核流程
```
用户消息 → 违规模板匹配（80%相似度→直接执行）
        ↘ 未命中 → DeepSeek AI异步分析（Semaphore限流10）
                ↘ AI判定违规 → PenaltyEngine评估 → 处罚/禁言/警告
                ↘ AI不确定 → 生成审核工单 → 人工复核
```

### 8.2 联邦信用分
- **初始分：100**，上限 100
- **自动加分**：每日 +1 分（新号 <7 天不享受）
- **处罚扣分**：诈骗/政治=死刑，色情=-30，广告=-5~10
- **动态禁言阈值**：35-70 自适应（新号=55，钻石用户≥80分=35）
- **奖励折扣**：黑产防刷 → 新号折扣 + 每日上限 + 同IP检测

### 8.3 多级熔断
| 级别 | 触发条件 | 动作 | 恢复 |
|------|---------|------|------|
| L1 群组 | QPS > 5/s 或 违规率 ≥30% | 慢速模式/消息丢弃 | 5 分钟自动恢复 |
| L2 用户 | 跨群违规 ≥5 次/30min | 忽略该用户消息 | 10 分钟恢复 |
| L3 全局 | DeepSeek 连续失败 | 规则引擎降级 | 5-30 分钟自动 |

### 8.4 冷启动保护
首次部署后 N 天（默认 7 天）内，所有处罚仅标记不执行，由管理员人工确认。

### 8.5 评分系统
- 1-5 星互评，ES 持久化存储
- 防作弊（同IP/同设备指纹检测）
- 排行榜 Top 奖励机制

---

## 9. 定时任务

| 任务 | 周期 | 说明 |
|------|------|------|
| 链接巡检 | 每日 03:00 | 检查联邦群组活跃度 |
| 画像分析 | 每日 04:00 | 跨群用户行为分析 |
| 每日加分 | 每日 05:00 | 活跃用户信用分恢复 |
| 排行榜结算 | 每日 06:00 | 月度排行结算 |
| 标签审计 | 每日 07:00 | 群组标签合规检查 |
| 熔断恢复 | 每 60 秒 | 过期群组熔断自动恢复 |

---

## 10. 安全设计

### 10.1 API 安全
- `X-Admin-Token` Header 验证管理接口
- `X-User-Id` 身份校验防越权查询
- CORS 白名单可配置（默认仅允许 localhost:8080）
- 全局异常处理器防信息泄露

### 10.2 数据安全
- application.properties 已加入 `.gitignore`
- 密码/Token 通过环境变量注入，不写入代码
- PostgreSQL 仅监听 localhost（生产建议防火墙限制）

### 10.3 AI 安全
- DeepSeek API 熔断后降级规则引擎
- AI 超时自动返回安全兜底
- Ollama 本地模式数据零外出

---

## 11. 数据存储

| 数据 | 存储 | 说明 |
|------|------|------|
| 用户/群组/机器人/代理 | PostgreSQL JPA | 核心业务数据 |
| 评分记录 | Elasticsearch | 评分聚合查询 |
| 缓存/限流 | Redis | 滑动窗口、信号量 |
| 运行时配置 | PostgreSQL + Redis | 双写一致性 |

---

## 12. 监控 & 日志

```bash
# 服务状态
systemctl status tg-federal-bot
journalctl -fu tg-federal-bot

# Docker 部署
docker compose logs -f app

# 健康检查
curl http://localhost:8080/actuator/health
```

---

## 13. 常见问题

### Webhook 设置失败
```bash
curl -X POST "https://api.telegram.org/bot<TOKEN>/setWebhook" \
  -d "url=https://你的域名/webhook/"
curl "https://api.telegram.org/bot<TOKEN>/getWebhookInfo"
```

### 国内服务器无法访问 Telegram API

**推荐方案：使用内置一键部署脚本**

`deploy/install.sh` 已集成 Clash Meta 代理自动安装，首次运行时会让你输入订阅链接，自动配置 SOCKS5 代理。详见 [3.2.1 一键脚本部署](#-321-一键脚本部署)。

**手动配置 SOCKS5 代理（不依赖一键脚本时）**

```bash
# 测试代理连通性
curl --socks5-hostname 127.0.0.1:7890 https://api.telegram.org
```

然后在 `application.properties` 或环境变量中配置：
```properties
bot.proxy.enabled=true
bot.proxy.socks5-host=127.0.0.1
bot.proxy.socks5-port=7890
```

或者通过 Docker 环境变量：
```bash
BOT_PROXY_ENABLED=true BOT_PROXY_HOST=127.0.0.1 BOT_PROXY_PORT=7890
```
### ES 启动失败（内存不足）
```bash
# 修改 ES JVM 配置：-Xms512m -Xmx512m
# Docker 环境已配置，无需手动操作
```

---

## 14. 升级维护

### Docker 部署升级
```bash
cd /opt/tg-federal-bot
docker compose build app
docker compose up -d app
```

### 传统部署升级
```bash
cd /opt/tg-federal-bot
# 如果项目是 git clone 的
git pull
# 或如果项目是脚本自动下载的，重新运行脚本即可
sudo bash deploy/install.sh
```

---

## 📄 License

MIT License

## 📮 联系方式

项目合作、定制开发：**nq9831@gmail.com**
