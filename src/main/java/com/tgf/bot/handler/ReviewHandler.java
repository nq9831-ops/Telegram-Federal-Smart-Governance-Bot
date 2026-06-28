package com.tgf.bot.handler;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import com.tgf.bot.service.FalsePositiveService;
import com.tgf.bot.service.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ReviewHandler — 审核命令处理器。
 * 
 * 工单审核系统的 Telegram 命令入口，支持工单列表/查看/核准/处罚/升级/批量处理。
 * @since 1.0
 */
@Component
@Order(1)
public class ReviewHandler implements BotHandler {

    private static final Logger log = LoggerFactory.getLogger(ReviewHandler.class);

    private final TicketService ticketService;
    private final FalsePositiveService falsePositiveService;

    @Value("${bot.creator:0}")
    private String creatorIdsStr;

    @Value("${bot.reviewers:}")
    private String reviewerIdsStr;


    public ReviewHandler(TicketService ticketService, FalsePositiveService falsePositiveService) {
        this.ticketService = ticketService;
        this.falsePositiveService = falsePositiveService;
    }

    @Override
    public boolean canHandle(Update update) {
        Message msg = update.message();
        if (msg == null || msg.text() == null) return false;
        String text = msg.text().trim();

        // 只有 /review 开头的命令
        return text.startsWith("/review") || text.startsWith("/review ");
    }

    @Override
    public void handle(Update update, TelegramBot bot) {
        Message msg = update.message();
        String text = msg.text().trim();
        Long chatId = msg.chat().id();
        User from = msg.from();
        if (from == null) return;

        // 权限检查：仅管理员可使用
        if (!isReviewer(from.id())) {
            reply(bot, chatId, "❌ 仅管理员可使用审核命令");
            return;
        }

        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            reply(bot, chatId, "📋 审核命令：\n"
                + "/review list - 待处理工单列表\n"
                + "/review view <id> - 查看工单详情\n"
                + "/review pass <id> - 核准放行\n"
                + "/review punish <id> <分数> - 人工裁定违规\n"
                + "/review escalate <id> - 升级工单\n"
                + "/review batch <id1,id2,...> pass|punish - 批量处理");
            return;
        }

        String action = parts[1];
        long reviewerId = from.id();

        try {
            switch (action) {
                case "list" -> handleList(bot, chatId, parts);
                case "view" -> handleView(bot, chatId, parts);
                case "pass" -> handlePass(bot, chatId, parts, reviewerId);
                case "punish" -> handlePunish(bot, chatId, parts, reviewerId);
                case "escalate" -> handleEscalate(bot, chatId, parts);
                case "batch" -> handleBatch(bot, chatId, parts, reviewerId);
                case "stats" -> handleStats(bot, chatId);
                default -> reply(bot, chatId, "❌ 未知子命令");
            }
        } catch (Exception e) {
            log.warn("Review command failed: {}", e.getMessage());
            reply(bot, chatId, "❌ 执行失败，请稍后重试或联系管理员");
        }
    }

    private void handleList(TelegramBot bot, Long chatId, String[] parts) {
        int page = 1;
        if (parts.length >= 3) {
            try { page = Integer.parseInt(parts[2]); } catch (Exception e) {
                log.debug("Invalid page number: {}", parts[2]);
            }
        }

        // 支持按类型筛选：/review list death_review 1
        String ticketType = null;
        if (parts.length >= 3 && !parts[2].matches("\\d+")) {
            ticketType = parts[2];
            if (parts.length >= 4) {
                try { page = Integer.parseInt(parts[3]); } catch (Exception e) {
                    log.debug("Invalid page number: {}", parts[3]);
                }
            }
        }

        var tickets = ticketService.listPendingTickets(ticketType, page, 20);
        if (tickets.isEmpty()) {
            reply(bot, chatId, "✅ 无待处理工单");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📋 待处理工单 (第").append(page).append("页)\n\n");

        for (var t : tickets) {
            String priorityEmoji = switch (t.getPriority()) {
                case 2 -> "🔴";
                case 1 -> "🟡";
                default -> "⚪";
            };
            sb.append(priorityEmoji).append(" #").append(t.getTicketId())
                .append(" [").append(t.getTicketType()).append("]")
                .append(" 目标: ").append(t.getTargetUserId())
                .append(" SLA: ").append(t.getDeadlineAt() != null ?
                    java.time.Duration.between(java.time.LocalDateTime.now(), t.getDeadlineAt()).toMinutes() + "min" : "无")
                .append("\n");
        }

        if (tickets.size() >= 20) {
            sb.append("\n... 下一页: /review list ").append(page + 1);
        }

        reply(bot, chatId, sb.toString());
    }

    private void handleView(TelegramBot bot, Long chatId, String[] parts) {
        if (parts.length < 3) {
            reply(bot, chatId, "用法：/review view <工单ID>");
            return;
        }

        long id = Long.parseLong(parts[2]);
        var ticket = ticketService.getTicket(id);
        if (ticket == null) {
            reply(bot, chatId, "❌ 工单不存在: #" + id);
            return;
        }

        String detail = """
            📋 工单 #%d
            
            类型: %s
            状态: %s
            优先级: %d
            提交者: %d
            目标用户: %d
            群组: %d
            
            内容: %s
            
            SLA截止: %s
            升级等级: %d
            冷启动: %s
            
            创建时间: %s
            """
            .formatted(
                ticket.getTicketId(), ticket.getTicketType(), ticket.getStatus(),
                ticket.getPriority(), ticket.getSubmitterId(), ticket.getTargetUserId(),
                ticket.getRelatedGroupId(),
                ticket.getContent() != null ? ticket.getContent().substring(0, Math.min(200, ticket.getContent().length())) : "无",
                ticket.getDeadlineAt(), ticket.getEscalationLevel(),
                ticket.isColdStart() ? "是" : "否",
                ticket.getCreatedAt()
            );

        reply(bot, chatId, detail);
    }

    private void handlePass(TelegramBot bot, Long chatId, String[] parts, long reviewerId) {
        if (parts.length < 3) {
            reply(bot, chatId, "用法：/review pass <工单ID> [备注]");
            return;
        }

        long id = Long.parseLong(parts[2]);
        String comment = parts.length >= 4 ? String.join(" ", Arrays.copyOfRange(parts, 3, parts.length)) : "审核通过";

        var ticket = ticketService.getTicket(id);
        if (ticket == null) {
            reply(bot, chatId, "❌ 工单不存在");
            return;
        }

        ticketService.passTicket(id, reviewerId, comment);

        // 如果是误判类通过，记录误报反馈
        if (comment.contains("误判")) {
            falsePositiveService.submitFeedback(id, reviewerId, "误判",
                "正常内容被误标为" + ticket.getTicketType(),
                ticket.getContent(), ticket.getTicketType(), 0.7, comment);
        }

        reply(bot, chatId, "✅ 工单 #" + id + " 已核准放行");
    }

    private void handlePunish(TelegramBot bot, Long chatId, String[] parts, long reviewerId) {
        if (parts.length < 3) {
            reply(bot, chatId, "用法：/review punish <工单ID> [备注]");
            return;
        }

        long id = Long.parseLong(parts[2]);
        String comment = parts.length >= 4 ? String.join(" ", Arrays.copyOfRange(parts, 3, parts.length)) : "人工裁定违规";

        ticketService.punishTicket(id, reviewerId, comment);
        reply(bot, chatId, "✅ 工单 #" + id + " 已裁定违规");
    }

    private void handleEscalate(TelegramBot bot, Long chatId, String[] parts) {
        if (parts.length < 3) {
            reply(bot, chatId, "用法：/review escalate <工单ID>");
            return;
        }

        long id = Long.parseLong(parts[2]);
        ticketService.escalateTicket(id);
        reply(bot, chatId, "✅ 工单 #" + id + " 已升级");
    }

    private void handleBatch(TelegramBot bot, Long chatId, String[] parts, long reviewerId) {
        if (parts.length < 4) {
            reply(bot, chatId, "用法：/review batch <id1,id2,...> pass|punish");
            return;
        }

        List<Long> ids = Arrays.stream(parts[2].split(","))
            .map(String::trim).filter(s -> !s.isEmpty())
            .map(Long::parseLong).collect(Collectors.toList());

        String action = parts[3];
        ticketService.batchProcess(ids, action, reviewerId);
        reply(bot, chatId, "✅ 批量处理 " + ids.size() + " 个工单: " + action);
    }

    private void handleStats(TelegramBot bot, Long chatId) {
        // 统计概览
        reply(bot, chatId, "📊 今日审核统计\n\n待完善");
    }

    private boolean isReviewer(long userId) {
        if (creatorIdsStr != null) {
            for (String s : creatorIdsStr.split(",")) {
                if (Long.parseLong(s.trim()) == userId) return true;
            }
        }
        if (reviewerIdsStr != null && !reviewerIdsStr.isBlank()) {
            for (String s : reviewerIdsStr.split(",")) {
                if (Long.parseLong(s.trim()) == userId) return true;
            }
        }
        return false;
    }
}
