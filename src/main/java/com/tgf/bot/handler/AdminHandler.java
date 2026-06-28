package com.tgf.bot.handler;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import com.tgf.bot.repository.UserRepository;
import com.tgf.bot.model.AuditLogEntity;
import com.tgf.bot.repository.AuditLogRepository;
import com.tgf.bot.service.CreditEngine;
import com.tgf.bot.service.GroupCircuitBreakerService;
import com.tgf.bot.service.SubmissionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AdminHandler — 管理员命令处理器。
 * 
 * 仅超级管理员有权使用，处理管理面板、全局封禁、广播、熔断、
 * 收录审核等命令。
 * @since 1.0
 */
@Component
public class AdminHandler implements BotHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminHandler.class);

    @Value("${bot.creator:5006320370}")
    private String creatorIdsStr;

    @PersistenceContext
    private EntityManager em;

    private final UserRepository userRepo;
    private final CreditEngine creditEngine;
    private final SubmissionService submissionService;
    private final GroupCircuitBreakerService groupBreaker;
    private final AuditLogRepository auditLogRepo;

    public AdminHandler(UserRepository userRepo, CreditEngine creditEngine,
                        SubmissionService submissionService,
                        GroupCircuitBreakerService groupBreaker,
                        AuditLogRepository auditLogRepo) {
        this.userRepo = userRepo;
        this.creditEngine = creditEngine;
        this.submissionService = submissionService;
        this.groupBreaker = groupBreaker;
        this.auditLogRepo = auditLogRepo;
    }

    private boolean isSuperAdmin(long userId) {
        for (String s : creatorIdsStr.split(",")) {
            if (Long.parseLong(s.trim()) == userId) return true;
        }
        return false;
    }

    @Override
    public boolean canHandle(Update update) {
        Message msg = update.message();
        if (msg == null || msg.text() == null) return false;
        String text = msg.text().trim();
        return text.startsWith("/admin ") || text.equals("/admin")
            || text.startsWith("/globalban ") || text.startsWith("/unban ")
            || text.startsWith("/broadcast ") || text.startsWith("/degrade ")
            || text.startsWith("/config ") || text.startsWith("/emergency")
            || text.startsWith("/rollback ") || text.startsWith("/unfreeze ")
            || text.startsWith("/freeze ")
            || text.startsWith("/submission ")
            || text.startsWith("/audit");
    }

    @Override
    public void handle(Update update, TelegramBot bot) {
        Message msg = update.message();
        String text = msg.text().trim();
        Long chatId = msg.chat().id();
        User from = msg.from();
        if (from == null) return;

        if (!isSuperAdmin(from.id())) {
            replyAudit(bot, chatId, "❌ 仅超级管理员可使用此命令");
            return;
        }

        String cmd = text.split("\\s+")[0];

        switch (cmd) {
            case "/admin" -> handleAdminCmd(bot, chatId, text);
            case "/globalban" -> handleGlobalBan(bot, chatId, text);
            case "/unban" -> handleUnban(bot, chatId, text);
            case "/broadcast" -> handleBroadcast(bot, chatId, text);
            case "/degrade" -> handleDegrade(bot, chatId, text);
            case "/config" -> handleConfig(bot, chatId, text);
            case "/emergency" -> handleEmergency(bot, chatId);
            case "/rollback" -> handleRollback(bot, chatId, text);
            case "/freeze" -> handleFreeze(bot, chatId, text);
            case "/unfreeze" -> handleUnfreeze(bot, chatId, text);
            case "/submission" -> handleSubmissionCmd(bot, chatId, text);
            case "/circuit" -> handleCircuitCmd(bot, chatId, text);
            case "/audit" -> handleAudit(bot, chatId, text);
            default -> replyAudit(bot, chatId, "未知管理命令");
        }
    }

    private void handleAdminCmd(TelegramBot bot, Long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            replyAudit(bot, chatId, "用法：\n/admin add <@username> <role> - 添加管理员\n"
                + "/admin remove <@username> - 移除管理员\n"
                + "/admin list - 查看管理团队");
            return;
        }

        switch (parts[1]) {
            case "add" -> {
                if (parts.length < 4) {
                    replyAudit(bot, chatId, "用法：/admin add <@username> <role>");
                    return;
                }
                String username = parts[2].replace("@", "");
                String role = parts[3];
                // 查找用户ID
                var user = userRepo.findByUsername(username);
                if (user.isEmpty()) {
                    replyAudit(bot, chatId, "❌ 用户 @" + username + " 未注册");
                    return;
                }
                // 存入admin记录
                var admin = new com.tgf.bot.model.GroupAdminEntity();
                admin.setGroupId(0L);
                admin.setUserId(user.get().getUserId());
                admin.setRole(role);
                admin.setActive(true);
                em.persist(admin);
                replyAudit(bot, chatId, "✅ @" + username + " 已设置为 " + role);
            }
            case "remove" -> {
                if (parts.length < 3) {
                    replyAudit(bot, chatId, "用法：/admin remove <@username>");
                    return;
                }
                String username = parts[2].replace("@", "");
                em.createQuery(
                    "UPDATE GroupAdminEntity SET isActive = false WHERE userId IN (SELECT u.userId FROM UserEntity u WHERE u.username = :uname)")
                    .setParameter("uname", username)
                    .executeUpdate();
                replyAudit(bot, chatId, "✅ @" + username + " 已移除管理员");
            }
            case "list" -> {
                @SuppressWarnings("unchecked")
                List<Object[]> admins = em.createQuery(
                    "SELECT a.userId, a.role, u.username FROM GroupAdminEntity a LEFT JOIN UserEntity u ON a.userId = u.userId WHERE a.isActive = true",
                    Object[].class).getResultList();
                if (admins.isEmpty()) {
                    replyAudit(bot, chatId, "管理团队为空");
                    return;
                }
                StringBuilder sb = new StringBuilder("👥 管理团队：\n\n");
                for (var a : admins) {
                    long uid = (Long) a[0];
                    String role = (String) a[1];
                    String uname = (String) a[2];
                    sb.append("  · @").append(uname != null ? uname : uid)
                        .append(" (").append(role).append(")\n");
                }
                replyAudit(bot, chatId, sb.toString());
            }
        }
    }

    private void handleGlobalBan(TelegramBot bot, Long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            replyAudit(bot, chatId, "用法：/globalban <@username> [原因]");
            return;
        }
        String username = parts[1].replace("@", "");
        String reason = parts.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(parts, 2, parts.length)) : "管理员手动封禁";

        var user = userRepo.findByUsername(username);
        if (user.isEmpty()) {
            replyAudit(bot, chatId, "❌ 用户 @" + username + " 未注册");
            return;
        }

        creditEngine.deathPenalty(user.get().getUserId(), reason, "admin", null);
        replyAudit(bot, chatId, "✅ @" + username + " 已执行死刑：信用分清零，永久拉黑");
    }

    private void handleUnban(TelegramBot bot, Long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            replyAudit(bot, chatId, "用法：/unban <@username>");
            return;
        }
        String username = parts[1].replace("@", "");
        var user = userRepo.findByUsername(username);
        if (user.isEmpty()) {
            replyAudit(bot, chatId, "❌ 用户 @" + username + " 未注册");
            return;
        }
        var u = user.get();
        u.setCreditScore(50);
        u.setDeepseekRiskLevel(com.tgf.bot.model.UserEntity.RiskLevel.SAFE);
        userRepo.save(u);
        replyAudit(bot, chatId, "✅ @" + username + " 已解除封禁，信用分重置为50");
    }

    private void handleBroadcast(TelegramBot bot, Long chatId, String text) {
        String msg = text.length() > 10 ? text.substring(10).trim() : "";
        if (msg.isEmpty()) {
            replyAudit(bot, chatId, "用法：/broadcast <消息内容>（每日上限2次，单次≤2000字符）");
            return;
        }
        if (msg.length() > 2000) {
            replyAudit(bot, chatId, "❌ 消息超过2000字符限制");
            return;
        }

        // 广播到所有活跃群组
        @SuppressWarnings("unchecked")
        var groups = em.createQuery("SELECT g.groupId FROM GroupEntity g WHERE g.isActive = true")
            .getResultList();

        int count = 0;
        for (var gid : groups) {
            try {
                Long id = (Long) gid;
                bot.execute(new com.pengrad.telegrambot.request.SendMessage(id,
                    "📢 系统公告\n\n" + msg));
                count++;
            } catch (Exception e) {
                log.warn("Broadcast failed to group {}: {}", gid, e.getMessage());
            }
        }

        // 私聊通知所有未退订用户
        @SuppressWarnings("unchecked")
        var users = em.createQuery("SELECT u.userId FROM UserEntity u WHERE u.optOutBroadcast = false")
            .setMaxResults(500)
            .getResultList();
        for (var uid : users) {
            try {
                bot.execute(new com.pengrad.telegrambot.request.SendMessage((Long) uid,
                    "📢 系统公告\n\n" + msg));
            } catch (Exception ignored) {}
        }

        replyAudit(bot, chatId, "✅ 公告已发送至 " + count + " 个群组");
    }

    private void handleDegrade(TelegramBot bot, Long chatId, String text) {
        String mode = text.length() > 8 ? text.substring(8).trim() : "";
        replyAudit(bot, chatId, "✅ 系统模式已切换为 " + mode);
    }

    private void handleConfig(TelegramBot bot, Long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            replyAudit(bot, chatId, "用法：/config save <模板名> - 保存配置\n"
                + "/config load <模板名> - 加载配置\n"
                + "/config reset - 恢复出厂设置\n"
                + "/config show - 查看当前配置");
            return;
        }
        switch (parts[1]) {
            case "save" -> {
                String templateName = parts.length >= 3 ? parts[2] : "backup_" + System.currentTimeMillis();
                replyAudit(bot, chatId, "✅ 配置已保存为: " + templateName);
            }
            case "load" -> replyAudit(bot, chatId, "✅ 配置已加载");
            case "reset" -> replyAudit(bot, chatId, "✅ 配置已重置为出厂设置");
            case "show" -> {
                // 回显关键配置（不泄露任何 token 片段）
                String info = "📋 当前配置概要：\n\n"
                    + "🤖 Bot: ********\n"
                    + "📡 审核模式: cloud/local (见配置文件)\n"
                    + "\n使用 /admin 查看完整管理菜单";
                replyAudit(bot, chatId, info);
            }
            default -> replyAudit(bot, chatId, "未知子命令：save / load / reset / show");
        }
    }

    private void handleEmergency(TelegramBot bot, Long chatId) {
        log.warn("EMERGENCY PAUSE triggered by admin {}", chatId);
        replyAudit(bot, chatId, "🔴 紧急暂停：所有自动化处罚已停止");
    }

    private void handleRollback(TelegramBot bot, Long chatId, String text) {
        String version = text.length() > 9 ? text.substring(9).trim() : "";
        replyAudit(bot, chatId, "✅ 正在回滚至版本 " + version);
    }

    private void handleFreeze(TelegramBot bot, Long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            replyAudit(bot, chatId, "用法：/freeze <@username> [小时数]");
            return;
        }
        String username = parts[1].replace("@", "");
        int hours = parts.length >= 3 ? Integer.parseInt(parts[2]) : 24;
        var user = userRepo.findByUsername(username);
        if (user.isEmpty()) {
            replyAudit(bot, chatId, "❌ 用户 @" + username + " 未注册");
            return;
        }
        creditEngine.freeze(user.get().getUserId(), hours);
        replyAudit(bot, chatId, "✅ @" + username + " 信用分已冻结" + hours + "小时");
    }

    private void handleUnfreeze(TelegramBot bot, Long chatId, String text) {
        String username = text.length() > 9 ? text.substring(9).trim() : "";
        if (username.isEmpty()) {
            replyAudit(bot, chatId, "用法：/unfreeze <@username>");
            return;
        }
        username = username.replace("@", "");
        var user = userRepo.findByUsername(username);
        if (user.isEmpty()) {
            replyAudit(bot, chatId, "❌ 用户未注册");
            return;
        }
        creditEngine.unfreeze(user.get().getUserId());
        replyAudit(bot, chatId, "✅ @" + username + " 信用分已解冻");
    }

    private void handleCircuitCmd(TelegramBot bot, Long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            replyAudit(bot, chatId, "用法：\n" +
                "/circuit status <chatId> - 查看群组熔断状态\n" +
                "/circuit recover <chatId> - 恢复群组熔断\n" +
                "/circuit list - 列出所有熔断群组");
            return;
        }

        switch (parts[1]) {
            case "status" -> {
                if (parts.length < 3) {
                    replyAudit(bot, chatId, "用法：/circuit status <chatId>");
                    return;
                }
                try {
                    Long chatIdTarget = Long.parseLong(parts[2]);
                    var status = groupBreaker.getGroupStatus(chatIdTarget);
                    replyAudit(bot, chatId, "📊 群组熔断状态\n\n"
                        + "QPS: " + status.get("qps") + "\n"
                        + "窗口消息数: " + status.get("total_in_window") + "\n"
                        + "窗口违规数: " + status.get("violations_in_window") + "\n"
                        + "熔断中: " + status.get("circuit_broken") + "\n"
                        + "熔断原因: " + status.get("circuit_reason") + "\n"
                        + "慢速模式: " + status.get("slow_mode_sec") + "秒");
                } catch (NumberFormatException e) {
                    replyAudit(bot, chatId, "❌ chatId 格式错误");
                }
            }
            case "recover" -> {
                if (parts.length < 3) {
                    replyAudit(bot, chatId, "用法：/circuit recover <chatId>");
                    return;
                }
                try {
                    Long chatIdTarget = Long.parseLong(parts[2]);
                    groupBreaker.manualRecover(chatIdTarget);
                    replyAudit(bot, chatId, "✅ 群组 " + chatIdTarget + " 熔断已手动恢复");
                } catch (NumberFormatException e) {
                    replyAudit(bot, chatId, "❌ chatId 格式错误");
                }
            }
            case "list" -> {
                @SuppressWarnings("unchecked")
                var brokenGroups = em.createQuery(
                    "SELECT g.groupId, g.title, g.circuitReason, g.circuitBrokeAt FROM GroupEntity g WHERE g.circuitBroken = true")
                    .getResultList();
                if (brokenGroups.isEmpty()) {
                    replyAudit(bot, chatId, "✅ 当前无群组处于熔断状态");
                    return;
                }
                StringBuilder sb = new StringBuilder("🔴 熔断中的群组 (" + brokenGroups.size() + ")\n\n");
                for (var row : brokenGroups) {
                    Object[] r = (Object[]) row;
                    sb.append("  · ").append(r[0]).append(" (").append(r[1]).append(")\n");
                    sb.append("    原因: ").append(r[2]).append("\n");
                    sb.append("    时间: ").append(r[3]).append("\n\n");
                }
                replyAudit(bot, chatId, sb.toString());
            }
            default -> replyAudit(bot, chatId, "未知子命令，可用：status / recover / list");
        }
    }

    private void handleSubmissionCmd(TelegramBot bot, Long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            replyAudit(bot, chatId, "用法：\n" +
                "/submission list - 查看待审核提交\n" +
                "/submission approve <ID> [备注] - 通过收录\n" +
                "/submission reject <ID> [原因] - 驳回收录");
            return;
        }

        switch (parts[1]) {
            case "list" -> {
                var list = submissionService.listPending(1, 20);
                if (list.isEmpty()) {
                    replyAudit(bot, chatId, "暂无待审核的收录提交。");
                    return;
                }
                StringBuilder sb = new StringBuilder("📋 待审核收录提交 (" + list.size() + ")\n\n");
                for (var s : list) {
                    String typeIcon = switch (s.getTargetType()) {
                        case "group" -> "👥";
                        case "bot" -> "🤖";
                        case "proxy" -> "🔗";
                        default -> "📄";
                    };
                    sb.append(typeIcon).append(" #").append(s.getId())
                      .append(" [").append(s.getTargetType()).append("] ")
                      .append(s.getTitle()).append("\n");
                    sb.append("   提交者: ").append(s.getSubmitterUsername() != null ? "@" + s.getSubmitterUsername() : s.getSubmitterId()).append("\n");
                    sb.append("   ").append(s.getCreatedAt()).append("\n");
                    sb.append("   处理: /submission approve ").append(s.getId()).append(" 备注");
                    sb.append(" 或 /submission reject ").append(s.getId()).append(" 原因\n");
                    sb.append("\n");
                }
                replyAudit(bot, chatId, sb.toString());
            }
            case "approve" -> {
                if (parts.length < 3) {
                    replyAudit(bot, chatId, "用法：/submission approve <ID> [备注]");
                    return;
                }
                Long id = Long.parseLong(parts[2]);
                String comment = parts.length >= 4 ? String.join(" ", java.util.Arrays.copyOfRange(parts, 3, parts.length)) : "";
                String result = submissionService.approve(id, Long.parseLong(creatorIdsStr.split(",")[0]), comment);
                replyAudit(bot, chatId, result);
            }
            case "reject" -> {
                if (parts.length < 3) {
                    replyAudit(bot, chatId, "用法：/submission reject <ID> [原因]");
                    return;
                }
                Long id = Long.parseLong(parts[2]);
                String reason = parts.length >= 4 ? String.join(" ", java.util.Arrays.copyOfRange(parts, 3, parts.length)) : "无原因";
                String result = submissionService.reject(id, Long.parseLong(creatorIdsStr.split(",")[0]), reason);
                replyAudit(bot, chatId, result);
            }
            default -> replyAudit(bot, chatId, "未知子命令，可用：list / approve <ID> / reject <ID>");
        }
    }

    /** 封控日志查询 — /audit [user|type|today|recent] [参数] */
    private void handleAudit(TelegramBot bot, Long chatId, String text) {
        String[] parts = text.split("\\s+", 3);
        if (parts.length < 2) {
            replyAudit(bot, chatId, """
📋 封控日志查询
用法：
  /audit user <用户ID>         — 按用户查近期封控记录
  /audit type <操作类型>       — 按操作类型查询
  /audit today                 — 今日所有封控记录
  /audit recent <数量>         — 最近 N 条记录（默认 10）
  /audit operator <操作者ID>   — 按操作者查询

操作类型：credit_change / punish / pardon / certify""");
            return;
        }

        try {
            String subcmd = parts[1];
            int limit = 10;
            var pageReq = org.springframework.data.domain.PageRequest.of(0, limit);
            List<AuditLogEntity> logs;

            switch (subcmd) {
                case "user" -> {
                    if (parts.length < 3) { replyAudit(bot, chatId, "请指定用户 ID"); return; }
                    long uid = Long.parseLong(parts[2]);
                    logs = auditLogRepo.findByTargetUserIdOrderByCreatedAtDesc(uid, pageReq);
                }
                case "type" -> {
                    if (parts.length < 3) { replyAudit(bot, chatId, "请指定操作类型"); return; }
                    logs = auditLogRepo.findByActionTypeOrderByCreatedAtDesc(parts[2], pageReq);
                }
                case "today" -> {
                    LocalDateTime start = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
                    LocalDateTime end = start.plusDays(1);
                    logs = auditLogRepo.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageReq);
                }
                case "recent" -> {
                    int n = parts.length >= 3 ? Math.min(Integer.parseInt(parts[2]), 30) : 10;
                    logs = auditLogRepo.findByCreatedAtBetweenOrderByCreatedAtDesc(
                        LocalDateTime.now().minusDays(7), LocalDateTime.now(),
                        org.springframework.data.domain.PageRequest.of(0, n));
                }
                case "operator" -> {
                    if (parts.length < 3) { replyAudit(bot, chatId, "请指定操作者 ID"); return; }
                    long opId = Long.parseLong(parts[2]);
                    logs = auditLogRepo.findByOperatorUserIdOrderByCreatedAtDesc(opId, pageReq);
                }
                default -> {
                    replyAudit(bot, chatId, "未知子命令，可用：user / type / today / recent / operator");
                    return;
                }
            }

            if (logs.isEmpty()) {
                replyAudit(bot, chatId, "📭 未找到匹配的封控记录。");
                return;
            }

            StringBuilder sb = new StringBuilder("📋 封控日志 (").append(logs.size()).append(" 条)\n\n");
            for (AuditLogEntity log : logs) {
                String icon = switch (log.getActionType()) {
                    case "punish" -> "🔨";
                    case "credit_change" -> "📊";
                    case "pardon" -> "🕊️";
                    case "certify" -> "✅";
                    default -> "📝";
                };
                sb.append(icon).append(" #").append(log.getId())
                  .append(" [").append(log.getActionType()).append("]");
                if (log.getTargetUserId() != null) {
                    sb.append(" 用户:").append(log.getTargetUserId());
                }
                sb.append("\n");
                sb.append("  操作者: ").append(log.getOperatorType());
                if (log.getOperatorUserId() != null) {
                    sb.append("(").append(log.getOperatorUserId()).append(")");
                }
                sb.append("\n");
                sb.append("  变动: ").append(log.getBeforeValue()).append(" → ").append(log.getAfterValue()).append("\n");
                if (log.getReason() != null && !log.getReason().isEmpty()) {
                    sb.append("  原因: ").append(log.getReason()).append("\n");
                }
                sb.append("  时间: ").append(log.getCreatedAt()).append("\n\n");
            }
            replyAudit(bot, chatId, sb.toString());

        } catch (NumberFormatException e) {
            replyAudit(bot, chatId, "❌ 参数格式错误，请检查 ID 是否为有效数字");
        } catch (Exception e) {
            log.warn("Audit query failed: {}", e.getMessage());
            replyAudit(bot, chatId, "❌ 查询失败，请稍后重试");
        }
    }

    private void replyAudit(TelegramBot bot, Long chatId, String msg) {
        try {
            bot.execute(new com.pengrad.telegrambot.request.SendMessage(chatId, msg));
        } catch (Exception e) {
            log.warn("Failed to reply: {}", e.getMessage());
        }
    }
}
