package com.tgf.bot.service;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * AggregatedNotifier — 聚合通知服务。
 *
 * 将零散告警/异动合并为 5 分钟窗口报告，
 * 避免高频通知轰炸管理员，支持按类型/群组/用户聚合统计。
 *
 * <p>通知类型：</p>
 * <ul>
 *   <li>CREDIT_ANOMALY — 信用分异动（±15 分以上）</li>
 *   <li>GROUP_CIRCUIT — 群组熔断</li>
 *   <li>USER_CIRCUIT — 用户熔断</li>
 *   <li>TICKET_ESCALATED — 工单超时升级</li>
 *   <li>DEATH_PENALTY — 死刑执行</li>
 *   <li>BATCH_DAILY — 每日汇总</li>
 * </ul>
 *
 * <p>依赖：</p>
 * <ul>
 *   <li>{@link TelegramBot} — 发送 Telegram 消息到管理员</li>
 * </ul>
 *
 * <p>被引用：</p>
 * <ul>
 *   <li>{@link CreditEngine} — 信用分异动/死刑通知</li>
 *   <li>{@link GroupCircuitBreakerService} — 群组熔断通知</li>
 *   <li>{@link TicketService} — 工单超时升级通知</li>
 * </ul>
 *
 * @since 1.0
 */
@Service
public class AggregatedNotifier {

    private static final Logger log = LoggerFactory.getLogger(AggregatedNotifier.class);

    // 通知类型
    public enum NotifyType {
        CREDIT_ANOMALY("信用分异动"),
        GROUP_CIRCUIT("群组熔断"),
        USER_CIRCUIT("用户熔断"),
        TICKET_ESCALATED("工单超时升级"),
        DEATH_PENALTY("死刑执行"),
        BATCH_DAILY("每日汇总");

        public final String label;
        NotifyType(String label) { this.label = label; }
    }

    public record AlertEvent(
        NotifyType type,
        String message,
        Map<String, Object> detail,
        long timestamp
    ) {}

    private final ConcurrentLinkedQueue<AlertEvent> alertQueue = new ConcurrentLinkedQueue<>();

    // Telegram Bot
    private final TelegramBot bot;

    @org.springframework.beans.factory.annotation.Value("${bot.admin-chat-id:0}")
    private long adminChatId;

    public AggregatedNotifier(TelegramBot bot) {
        this.bot = bot;
    }

    /** 推入一条异动事件 */
    public void push(NotifyType type, String message, Map<String, Object> detail) {
        alertQueue.add(new AlertEvent(type, message, detail, System.currentTimeMillis()));
    }

    /** 简化接口 */
    public void pushCreditAnomaly(long userId, int before, int after, String reason) {
        push(NotifyType.CREDIT_ANOMALY,
            "用户 " + userId + " 信用分异动: " + before + " → " + after + " (" + reason + ")",
            Map.of(
                "userId", userId,
                "before", before,
                "after", after,
                "change", after - before,
                "reason", reason
            ));
    }

    public void pushGroupCircuit(Long chatId, String reason) {
        push(NotifyType.GROUP_CIRCUIT,
            "群组 " + chatId + " 熔断: " + reason,
            Map.of("chatId", chatId, "reason", reason));
    }

    public void pushDeathPenalty(long userId, String reason) {
        push(NotifyType.DEATH_PENALTY,
            "用户 " + userId + " 被执行死刑: " + reason,
            Map.of("userId", userId, "reason", reason));
    }

    /** 定时 5 分钟合并发送 — 由 BotScheduler 调用 */
    @Scheduled(fixedRate = 300000)
    public void flush() {
        if (alertQueue.isEmpty()) return;

        List<AlertEvent> batch = new ArrayList<>();
        AlertEvent ev;
        while ((ev = alertQueue.poll()) != null) {
            batch.add(ev);
        }

        if (batch.isEmpty()) return;

        // 按类型聚合
        Map<NotifyType, List<AlertEvent>> grouped = batch.stream()
            .collect(Collectors.groupingBy(AlertEvent::type));

        StringBuilder sb = new StringBuilder();
        sb.append("📊 **系统异动报告** (").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
          .append(")\n")
          .append("窗口: 约 5 分钟 | 事件数: ").append(batch.size()).append("\n\n");

        for (var entry : grouped.entrySet()) {
            NotifyType type = entry.getKey();
            List<AlertEvent> events = entry.getValue();
            sb.append("**").append(type.label).append("** (").append(events.size()).append(" 条)\n");

            // 最多显示 8 条摘要，其余标记
            int shown = 0;
            for (AlertEvent e : events) {
                if (shown >= 8) {
                    sb.append("  ... 还有 ").append(events.size() - shown).append(" 条\n");
                    break;
                }
                String msg = e.message().length() > 60
                    ? e.message().substring(0, 60) + "..."
                    : e.message();
                sb.append("  · ").append(msg).append("\n");
                shown++;
            }
            sb.append("\n");
        }

        // 统计维度的关键数字
        Map<String, Long> stats = new LinkedHashMap<>();
        for (var entry : grouped.entrySet()) {
            stats.put(entry.getKey().label, (long) entry.getValue().size());
        }
        sb.append("---\n📌 **关键数字**\n");
        stats.forEach((k, v) -> sb.append("  · ").append(k).append(": ").append(v).append(" 次\n"));

        String report = sb.toString();

        // 发送到管理员（如果消息超长则分片）
        if (adminChatId != 0L) {
            int maxLen = 4096;
            if (report.length() <= maxLen) {
                try {
                    bot.execute(new SendMessage(adminChatId, report));
                } catch (Exception e) {
                    log.warn("Failed to send aggregated report: {}", e.getMessage());
                }
            } else {
                // 分片发送
                int start = 0;
                int part = 1;
                while (start < report.length()) {
                    int end = Math.min(start + maxLen, report.length());
                    // 尽量在换行符处分片，避免截断英文单词
                    if (end < report.length()) {
                        int newlineBreak = report.lastIndexOf('\n', end);
                        if (newlineBreak > start + maxLen / 2) {
                            end = newlineBreak;
                        }
                    }
                    String chunk = "(" + part + "/...)\n" + report.substring(start, end);
                    try {
                        bot.execute(new SendMessage(adminChatId, chunk));
                    } catch (Exception e) {
                        log.warn("Failed to send aggregated report part {}: {}", part, e.getMessage());
                    }
                    part++;
                    start = end;
                }
            }
        }

        // 日志记录
        log.info("Aggregated report flushed: {} events in {} types",
            batch.size(), grouped.size());
    }
}
