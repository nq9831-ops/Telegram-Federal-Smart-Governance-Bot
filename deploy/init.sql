-- tg-federal-bot 数据库初始化脚本
-- 用于首次部署时创建数据库及所有表结构

-- =====================
-- 1. 创建数据库
-- =====================
CREATE DATABASE tg_federal_bot WITH ENCODING 'UTF8' LC_COLLATE 'C' LC_CTYPE 'C' TEMPLATE template0;

\c tg_federal_bot;

-- =====================
-- 2. 用户表
-- =====================
CREATE TABLE IF NOT EXISTS users (
    user_id BIGINT PRIMARY KEY,                     -- Telegram User ID
    username VARCHAR(64),
    credit_score INT DEFAULT 100,
    deepseek_risk_level VARCHAR(16) DEFAULT 'SAFE',
    device_fingerprint_hash VARCHAR(128),
    is_certified_advertiser BOOLEAN DEFAULT FALSE,
    cert_expire_at TIMESTAMP,
    is_frozen BOOLEAN DEFAULT FALSE,
    frozen_until TIMESTAMP,
    is_group_jumper BOOLEAN DEFAULT FALSE,
    daily_ad_count INT DEFAULT 0,
    daily_reset_at TIMESTAMP,
    invite_code VARCHAR(32),
    invite_count INT DEFAULT 0,
    lang VARCHAR(8) DEFAULT 'zh',
    privacy_accepted BOOLEAN DEFAULT FALSE,
    privacy_accepted_at TIMESTAMP,
    is_underage BOOLEAN,
    opt_out_broadcast BOOLEAN DEFAULT FALSE,
    profile_completeness INT DEFAULT 0,
    is_reviewer BOOLEAN DEFAULT FALSE,
    trusted_seed BOOLEAN DEFAULT FALSE,
    invited_by BIGINT,
    registration_ip VARCHAR(45),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_credit_score ON users(credit_score);
CREATE INDEX IF NOT EXISTS idx_device_fingerprint ON users(device_fingerprint_hash);
CREATE INDEX IF NOT EXISTS idx_username ON users(username);

-- =====================
-- 3. 群组表
-- =====================
CREATE TABLE IF NOT EXISTS groups (
    group_id BIGINT PRIMARY KEY,                    -- Telegram Chat ID
    title VARCHAR(128),
    username VARCHAR(64),
    description TEXT,
    member_count BIGINT,
    admin_count BIGINT,
    group_label VARCHAR(16) DEFAULT 'NONE',
    label_audit_violations INT DEFAULT 0,
    label_reapply_forbidden BOOLEAN DEFAULT FALSE,
    invite_link VARCHAR(64),
    environment_score INT DEFAULT 100,
    is_active BOOLEAN DEFAULT TRUE,
    circuit_broken BOOLEAN DEFAULT FALSE,
    circuit_reason VARCHAR(128),
    circuit_broke_at TIMESTAMP,
    circuit_recover_at TIMESTAMP,
    slow_mode_sec INT DEFAULT 0,
    invalidated_at TIMESTAMP,
    invalid_reason VARCHAR(256),
    label_expire_at TIMESTAMP,
    cert_expire_at TIMESTAMP,
    cold_start_pending BOOLEAN DEFAULT FALSE,
    whitelist_user_ids TEXT,
    pinned_announce_msg_id BIGINT,
    announce_text VARCHAR(4096),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_group_active ON groups(is_active);
CREATE INDEX IF NOT EXISTS idx_group_label ON groups(group_label);
CREATE INDEX IF NOT EXISTS idx_env_score ON groups(environment_score);

-- =====================
-- 4. 消息记录表
-- =====================
CREATE TABLE IF NOT EXISTS messages (
    message_id BIGINT PRIMARY KEY,                  -- Telegram Message ID
    group_id BIGINT,
    user_id BIGINT,
    text TEXT,
    content_fingerprint VARCHAR(64),
    category VARCHAR(32),
    confidence DOUBLE PRECISION DEFAULT 0.0,
    brief_reason VARCHAR(512),
    is_deleted BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_msg_user ON messages(user_id);
CREATE INDEX IF NOT EXISTS idx_msg_group ON messages(group_id);
CREATE INDEX IF NOT EXISTS idx_msg_time ON messages(created_at);
CREATE INDEX IF NOT EXISTS idx_msg_fingerprint ON messages(content_fingerprint);

-- =====================
-- 5. 审计日志表
-- =====================
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGSERIAL PRIMARY KEY,
    operator_type VARCHAR(16),
    operator_user_id BIGINT,
    action_type VARCHAR(32),
    target_user_id BIGINT,
    target_group_id BIGINT,
    before_value INT DEFAULT 0,
    after_value INT DEFAULT 0,
    reason VARCHAR(1024),
    deepseek_analysis_id VARCHAR(64),
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_audit_operator ON audit_log(operator_type);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_log(action_type);
CREATE INDEX IF NOT EXISTS idx_audit_user ON audit_log(target_user_id);
CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_log(created_at);

-- =====================
-- 6. 机器人表
-- =====================
CREATE TABLE IF NOT EXISTS bots (
    bot_id BIGINT PRIMARY KEY,                      -- Telegram Bot ID
    bot_username VARCHAR(128),
    bot_name VARCHAR(128),
    bot_credit_score INT DEFAULT 100,
    api_call_count BIGINT DEFAULT 0,
    avg_response_ms DOUBLE PRECISION DEFAULT 0.0,
    removed_from_groups INT DEFAULT 0,
    violation_hit_count INT DEFAULT 0,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_bot_credit ON bots(bot_credit_score);

-- =====================
-- 7. 代理表
-- =====================
CREATE TABLE IF NOT EXISTS proxies (
    id BIGSERIAL PRIMARY KEY,
    protocol VARCHAR(16),
    endpoint VARCHAR(256),
    is_free BOOLEAN DEFAULT FALSE,
    country_code VARCHAR(8),
    online_rate DOUBLE PRECISION DEFAULT 0.0,
    exit_ip_risk_label VARCHAR(128),
    proxy_credit_score INT DEFAULT 100,
    is_active BOOLEAN DEFAULT TRUE,
    invalidated_at TIMESTAMP,
    invalid_reason VARCHAR(256),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_proxy_active ON proxies(is_active);
CREATE INDEX IF NOT EXISTS idx_proxy_credit ON proxies(proxy_credit_score);

-- =====================
-- 8. 收录提交表
-- =====================
CREATE TABLE IF NOT EXISTS submissions (
    id BIGSERIAL PRIMARY KEY,
    target_type VARCHAR(16),
    target_id VARCHAR(256),
    title VARCHAR(128),
    description TEXT,
    contact VARCHAR(64),
    invite_link VARCHAR(256),
    group_label VARCHAR(16),
    protocol VARCHAR(16),
    endpoint VARCHAR(256),
    submitter_id BIGINT,
    submitter_username VARCHAR(64),
    status VARCHAR(16) DEFAULT 'PENDING',
    reviewer_id BIGINT,
    reviewed_at TIMESTAMP,
    review_comment VARCHAR(512),
    ticket_id BIGINT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sub_status ON submissions(status);
CREATE INDEX IF NOT EXISTS idx_sub_submitter ON submissions(submitter_id);
CREATE INDEX IF NOT EXISTS idx_sub_target_type ON submissions(target_type);

-- =====================
-- 9. 审核工单表
-- =====================
CREATE TABLE IF NOT EXISTS tickets (
    ticket_id BIGSERIAL PRIMARY KEY,
    ticket_type VARCHAR(32),
    status VARCHAR(16) DEFAULT 'PENDING',
    priority INT DEFAULT 0,
    submitter_id BIGINT,
    target_user_id BIGINT,
    content TEXT,
    deepseek_analysis_id VARCHAR(64),
    reviewer_id BIGINT,
    reviewed_at TIMESTAMP,
    review_comment VARCHAR(512),
    deadline_at TIMESTAMP,
    escalated_at TIMESTAMP,
    escalation_level INT DEFAULT 0,
    cold_start BOOLEAN DEFAULT FALSE,
    related_group_id BIGINT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ticket_type ON tickets(ticket_type);
CREATE INDEX IF NOT EXISTS idx_ticket_status ON tickets(status);
CREATE INDEX IF NOT EXISTS idx_ticket_priority ON tickets(priority);
CREATE INDEX IF NOT EXISTS idx_ticket_deadline ON tickets(deadline_at);

-- =====================
-- 10. 系统配置表
-- =====================
CREATE TABLE IF NOT EXISTS system_config (
    config_key VARCHAR(64) PRIMARY KEY,
    config_value TEXT,
    config_type VARCHAR(16),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- =====================
-- 11. 群组管理员表
-- =====================
CREATE TABLE IF NOT EXISTS group_admins (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT,
    user_id BIGINT,
    role VARCHAR(32),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ga_group ON group_admins(group_id);
CREATE INDEX IF NOT EXISTS idx_ga_user ON group_admins(user_id);
CREATE INDEX IF NOT EXISTS idx_ga_active ON group_admins(is_active);

-- =====================
-- 12. 误报反馈表
-- =====================
CREATE TABLE IF NOT EXISTS false_positive_feedback (
    id BIGSERIAL PRIMARY KEY,
    ticket_id BIGINT,
    reviewer_id BIGINT,
    feedback_category VARCHAR(32),
    misclassify_type VARCHAR(64),
    original_text TEXT,
    deepseek_category VARCHAR(32),
    deepseek_confidence DOUBLE PRECISION DEFAULT 0.0,
    comment VARCHAR(512),
    report_week INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_fp_category ON false_positive_feedback(feedback_category);
CREATE INDEX IF NOT EXISTS idx_fp_week ON false_positive_feedback(report_week);

-- =====================
-- 13. 评分规则版本表
-- =====================
CREATE TABLE IF NOT EXISTS rating_rules (
    id BIGSERIAL PRIMARY KEY,
    rule_version INT DEFAULT 0,
    rule_key VARCHAR(64),
    rule_name VARCHAR(32),
    penalty_value INT DEFAULT 0,
    description TEXT,
    changed_by VARCHAR(64),
    effective_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);

-- =====================
-- 14. 退群记录表
-- =====================
CREATE TABLE IF NOT EXISTS user_leaves (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    group_id BIGINT,
    left_at TIMESTAMP DEFAULT NOW(),
    left_type VARCHAR(16),
    current_credit_score INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ul_user ON user_leaves(user_id);
CREATE INDEX IF NOT EXISTS idx_ul_group ON user_leaves(group_id);
CREATE INDEX IF NOT EXISTS idx_ul_time ON user_leaves(left_at);

-- =====================
-- 15. 版本日志表
-- =====================
CREATE TABLE IF NOT EXISTS version_log (
    id BIGSERIAL PRIMARY KEY,
    version VARCHAR(16),
    release_date TIMESTAMP,
    changelog TEXT,
    is_major BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT NOW()
);

-- =====================
-- 16. 违规模板表
-- =====================
CREATE TABLE IF NOT EXISTS violation_template (
    template_id BIGSERIAL PRIMARY KEY,
    template_text TEXT,
    category VARCHAR(32),
    hit_count INT DEFAULT 0,
    status VARCHAR(16) DEFAULT 'PENDING',
    first_seen_at TIMESTAMP DEFAULT NOW(),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_template_category ON violation_template(category);
CREATE INDEX IF NOT EXISTS idx_template_status ON violation_template(status);
CREATE INDEX IF NOT EXISTS idx_template_hit ON violation_template(hit_count);

-- =====================
-- 违规模板预设数据
-- =====================
INSERT INTO violation_template (template_text, category, hit_count, status, first_seen_at, created_at, updated_at)
SELECT templateText, category, 0, 'ACTIVE', NOW(), NOW(), NOW()
FROM (VALUES
    -- 🚫 常见推广/代理关键词
    ('代理',                                              '普通广告'),
    ('接单',                                              '普通广告'),
    ('tg代理',                                            '普通广告'),
    ('跑分',                                              '诈骗'),
    ('usdt 承兑',                                         '普通广告'),
    ('虚拟货币 搬砖',                                     '普通广告'),
    ('刷单',                                              '诈骗'),
    ('日结',                                              '普通广告'),
    ('日薪',                                              '普通广告'),
    ('兼职 日入',                                         '普通广告'),
    ('无门槛 赚钱',                                       '诈骗'),
    ('扫码 返利',                                         '诈骗'),
    ('投资 稳赚',                                         '诈骗'),
    ('跟单 带单',                                         '诈骗'),
    ('杀猪盘',                                            '诈骗'),
    ('数字货币 带单',                                     '诈骗'),
    ('交易所 返佣',                                       '诈骗'),
    ('翻墙机场 推荐',                                     '普通广告'),
    ('ssr 节点',                                          '普通广告'),
    ('v2ray 订阅',                                        '普通广告'),
    ('clash 订阅',                                        '普通广告'),
    ('免费节点',                                          '普通广告'),
    ('高防 CDN',                                          '普通广告'),
    ('DDOS 防御',                                         '普通广告'),
    ('棋牌 搭建',                                         '赌博'),
    ('菠菜 源码',                                         '赌博'),
    ('时时彩 平台',                                       '赌博'),
    ('百家乐 代理',                                       '赌博'),
    ('真人视讯',                                          '赌博'),
    ('澳门 赌场',                                         '赌博'),
    ('性感 直播',                                         '色情'),
    ('约炮',                                              '色情'),
    ('裸聊',                                              '色情'),
    ('色情 网站',                                         '色情'),
    ('AV 资源',                                           '色情'),
    ('成人 影片',                                         '色情'),
    ('群发 软件',                                         '普通广告'),
    ('电报 群发',                                         '普通广告'),
    ('tg 引流',                                           '普通广告'),
    ('精准 粉',                                           '普通广告'),
    ('加人 软件',                                         '普通广告'),
    ('采集 群',                                           '普通广告')
) AS t(templateText, category)
WHERE NOT EXISTS (
    SELECT 1 FROM violation_template v WHERE v.template_text = t.templateText
);

-- 分表建议（高并发场景）：
-- 消息表按月分区：
-- CREATE TABLE messages_202606 PARTITION OF messages FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
-- 审计日志按季度分区：
-- CREATE TABLE audit_log_2026q2 PARTITION OF audit_log FOR VALUES FROM ('2026-04-01') TO ('2026-07-01');
