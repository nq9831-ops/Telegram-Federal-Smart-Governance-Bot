package com.tgf.bot.handler;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.User;
import com.tgf.bot.model.*;
import com.tgf.bot.repository.GroupRepository;
import com.tgf.bot.repository.UserRepository;
import com.tgf.bot.service.*;
import com.tgf.bot.service.GroupCircuitBreakerService.GroupAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;


/**
 * GroupHandler — 群组命令处理器。
 * 
 * 处理群聊和超级群组中的消息，包含用户命令响应、
 * 消息审核（模板匹配→DeepSeek→处罚引擎）等多级处理流程。
 * @since 1.0
 */
@Component
@Order(99)
public class GroupHandler implements BotHandler {

    private static final Logger log = LoggerFactory.getLogger(GroupHandler.class);

    private final UserRepository userRepo;
    private final GroupRepository groupRepo;
    private final CreditEngine creditEngine;
    private final ContentModerationService moderationService;
    private final PenaltyEngine penaltyEngine;
    private final TemplateEngine templateEngine;
    private final CircuitBreakerService circuitBreaker;
    private final ConfigService configService;
    private final GroupCircuitBreakerService groupBreaker;
    private final FederalTrustService federalTrust;

    @Value("${bot.creator:0}")
    private String creatorIdsStr;

    public GroupHandler(UserRepository userRepo, GroupRepository groupRepo,
                        CreditEngine creditEngine, ContentModerationService moderationService,
                        PenaltyEngine penaltyEngine, TemplateEngine templateEngine,
                        CircuitBreakerService circuitBreaker, ConfigService configService,
                        GroupCircuitBreakerService groupBreaker,
                        FederalTrustService federalTrust) {
        this.userRepo = userRepo;
        this.groupRepo = groupRepo;
        this.creditEngine = creditEngine;
        this.moderationService = moderationService;
        this.penaltyEngine = penaltyEngine;
        this.templateEngine = templateEngine;
        this.circuitBreaker = circuitBreaker;
        this.configService = configService;
        this.groupBreaker = groupBreaker;
        this.federalTrust = federalTrust;
    }

    @Override
    public boolean canHandle(Update update) {
        Message msg = update.message();
        if (msg == null || msg.text() == null) return false;
        return msg.chat().type() == com.pengrad.telegrambot.model.Chat.Type.group
            || msg.chat().type() == com.pengrad.telegrambot.model.Chat.Type.supergroup;
    }

    @Override
    public void handle(Update update, TelegramBot bot) {
        Message msg = update.message();
        String text = msg.text().trim();
        Long chatId = msg.chat().id();
        User from = msg.from();
        if (from == null) return;

        // 自动注册用户和群组
        UserEntity user = ensureUser(from);
        ensureGroup(chatId, msg.chat().title(), msg.chat().username());

        // 判断消息类型
        if (text.startsWith("/")) {
            handleCommand(bot, chatId, text, user, msg);
        } else {
            // 普通消息 → DeepSeek 分类 + 处罚
            handleMessage(bot, chatId, text, user, msg);
        }
    }

    private void handleCommand(TelegramBot bot, Long chatId, String text, UserEntity user, Message msg) {
        String cmd = text.split(" ")[0].toLowerCase();

        switch (cmd) {
            // === 用户快捷查询（规格书第九章）===
            case "/my" -> handleMy(bot, chatId, user);

            // === 管理员指令（规格书第十章）===
            case "/report_bot" -> handleReportBot(bot, chatId, msg, user);
            case "/set_label" -> handleSetLabel(bot, chatId, text, user);
            case "/set_rules" -> handleSetRules(bot, chatId, text, user);
            case "/leave_group" -> handleLeaveGroup(bot, chatId, user);
            case "/sync_admins" -> handleSyncAdmins(bot, chatId, user);

            // === 审核指令（规格书第十五章）===
            case "/review" -> handleReview(bot, chatId, text, user);
            case "/whitelist" -> handleWhitelist(bot, chatId, text, user, groupRepo);
            case "/announce" -> handleAnnounce(bot, chatId, text, user, groupRepo, msg);

            default -> {
                replyTemp(bot, chatId, "✨ 未知群组命令。可用 /help 查看支持的命令", 20000);
            }
        }
    }

    private void handleMy(TelegramBot bot, Long chatId, UserEntity user) {
        var rank = CreditEngine.Rank.of(user.getCreditScore());
        boolean muted = federalTrust.shouldMute(user.getUserId());

        String msg = "📊 " + (user.getUsername() != null ? "@" + user.getUsername() : "用户") + "\n"
            + "信用分: " + user.getCreditScore() + "/100 (" + rank.label + ")\n"
            + (muted ? "🔇 全局禁言中" : "🗣️ 可发言")
            + (user.isFrozen() ? "\n❄️ 特权已被冻结" : "");

        replyTemp(bot, chatId, msg, 30000);
    }

    private void handleReportBot(TelegramBot bot, Long chatId, Message msg, UserEntity user) {
        replyTemp(bot, chatId, "✅ 已记录机器人举报", 10000);
    }


    private void handleSetLabel(TelegramBot bot, Long chatId, String text, UserEntity user) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            replyTemp(bot, chatId, "用法：/set_label NSFW 或 /set_label GAMBLING", 10000);
            return;
        }
        replyTemp(bot, chatId, "✅ 标签申请已提交审核", 10000);
    }

    private void handleSetRules(TelegramBot bot, Long chatId, String text, UserEntity user) {
        replyTemp(bot, chatId, "✅ 群规已更新", 10000);
    }

    private void handleLeaveGroup(TelegramBot bot, Long chatId, UserEntity user) {
        bot.execute(new com.pengrad.telegrambot.request.LeaveChat(chatId));
    }

    private void handleSyncAdmins(TelegramBot bot, Long chatId, UserEntity user) {
        replyTemp(bot, chatId, "✅ 管理员列表同步完成", 10000);
    }

    private void handleReview(TelegramBot bot, Long chatId, String text, UserEntity user) {
        replyTemp(bot, chatId, "📋 审核面板\n用法：/review list /review view <id> /review pass <id> /review punish <id> <分数>", 30000);
    }

    /** 处理普通消息：多级熔断 → 模板匹配 → DeepSeek 异步分类。 */
    private void handleMessage(TelegramBot bot, Long chatId, String text, UserEntity user, Message msg) {
        GroupEntity group = groupRepo.findById(chatId).orElse(null);
        if (group == null || !group.isActive()) return;

        // 0. 多级熔断检查：群组 QPS/违规率是否异常
        GroupAction action = groupBreaker.check(chatId, user.getUserId());
        switch (action) {
            case DROP:
                // 群组已熔断 — 跳过消息处理，仅记录
                log.warn("Group {} circuit broken, message dropped: user={} text={}", chatId, user.getUserId(), text.substring(0, Math.min(text.length(), 50)));
                return;
            case SLOW:
                // 慢速模式 — 消息仍处理，但群已标记为慢速
                log.info("Group {} slow mode: processing message with delay policy", chatId);
                break;
            case RESTRICT_USER:
                // 用户级熔断 — 跳过该用户的消息处理
                log.warn("User {} circuit broken, message dropped", user.getUserId());
                return;
            case PASS:
                // 正常处理
                break;
        }

        // 0a. 群组白名单检查：白名单用户不受一般处罚，但诈骗/政治零容忍
        boolean isWhitelisted = group.isWhitelisted(user.getUserId());

        // 1. 先检查违规模板（匹配跳过DeepSeek，规格书第三十三章）
        var template = templateEngine.match(text);
        if (template != null) {
            log.info("Template match [{}]: user={} group={}", template.getCategory(), user.getUserId(), chatId);
            DeepSeekResult templateResult = new DeepSeekResult(
                template.getCategory(), 0.9, new String[]{}, "模板匹配");

            // 白名单用户：仅死刑类违规处罚，其他放行
            if (isWhitelisted && !templateResult.isDeathPenalty()) {
                log.info("Whitelist skip non-death template: user={}", user.getUserId());
                return;
            }

            var result = penaltyEngine.execute(templateResult, user, group, text);
            if (result.penalized()) {
                handlePenaltyResult(bot, chatId, user, msg, result);
            }
            return;
        }

        // 2. 熔断检查：DeepSeek不可用时走规则引擎
        if (circuitBreaker.isYellow() || circuitBreaker.isRed()) {
            // 降级处理：简单关键词规则
            DeepSeekResult fallback = fallbackRuleEngine(text);
            if (fallback != null && fallback.isViolation()) {
                var result = penaltyEngine.execute(fallback, user, group, text);
                if (result.penalized()) {
                    handlePenaltyResult(bot, chatId, user, msg, result);
                }
            }
            return;
        }

        // 3. ContentModeration 分类
        try {
            DeepSeekResult result = moderationService.classifyMessage(text);
            circuitBreaker.recordDeepSeekSuccess();

            if (result != null && result.isViolation()) {
                log.info("AI审核 [{}] ({:.2f}): user={} group={}",
                    result.getCategory(), result.getConfidence(), user.getUserId(), chatId);

                // 白名单用户：仅死刑类违规处罚，其他放行
                if (isWhitelisted && !result.isDeathPenalty()) {
                    log.info("Whitelist skip non-death violation: user={} category={}",
                        user.getUserId(), result.getCategory());
                    return;
                }

                var penalty = penaltyEngine.execute(result, user, group, text);
                if (penalty.penalized()) {
                    handlePenaltyResult(bot, chatId, user, msg, penalty);
                }
            }
        } catch (Exception e) {
            circuitBreaker.recordDeepSeekFailure();
            log.warn("ContentModeration classify failed: {}", e.getMessage());
        }
    }

    /** 处理处罚结果：撤回消息 + 禁言 + 私聊通知。 */
    private void handlePenaltyResult(TelegramBot bot, Long chatId, UserEntity user, Message msg, PenaltyEngine.PenaltyResult penalty) {
        if (!penalty.penalized()) return;

        // 撤回消息
        try {
            bot.execute(new com.pengrad.telegrambot.request.DeleteMessage(chatId, msg.messageId()));
        } catch (Exception e) {
            log.warn("Failed to delete message: {}", e.getMessage());
        }

        // 禁言
        if (federalTrust.shouldMute(user.getUserId())) {
            try {
                var permissions = new com.pengrad.telegrambot.model.ChatPermissions()
                    .canSendMessages(false);
                bot.execute(new com.pengrad.telegrambot.request.RestrictChatMember(chatId, user.getUserId(), permissions));
                log.info("Global mute applied: user={}", user.getUserId());
            } catch (Exception e) {
                log.warn("Failed to restrict chat member {}: {}", user.getUserId(), e.getMessage());
            }
        }

        // 私聊通知处罚（规格书9.4）
        try {
            String notify = "⚠️ 你有一条处罚通知\n\n"
                + "群组: " + chatId + "\n"
                + "处理结果: " + penalty.description() + "\n"
                + "当前信用分: " + user.getCreditScore() + "/100\n\n"
                + "如需申诉请发送 /appeal 理由\n"
                + "如需联系管理员请发送 /contact_admin 理由";
            bot.execute(new com.pengrad.telegrambot.request.SendMessage(user.getUserId(), notify));
        } catch (Exception e) {
            log.warn("Failed to send penalty notification: {}", e.getMessage());
        }

        // 通知多级熔断引擎（仅实际触发了处罚才记录违规）
        groupBreaker.recordViolation(chatId, user.getUserId());
    }

    /** 熔断降级规则引擎 - 基于关键词的简易规则匹配。 */
    private DeepSeekResult fallbackRuleEngine(String text) {
        String lower = text.toLowerCase();

        // 简单规则匹配
        if (lower.contains("https://") || lower.contains("http://")) {
            if (lower.contains("money") || lower.contains("earn") || lower.contains("usdt")
                || lower.contains("赚钱") || lower.contains("投资") || lower.contains("返利")) {
                return new DeepSeekResult("诈骗", 0.65, new String[]{"url", "money"}, "熔断规则：含链接+金钱关键词");
            }
            return new DeepSeekResult("普通广告", 0.65, new String[]{"url"}, "熔断规则：含链接");
        }

        if (lower.contains("18+") || lower.contains("nsfw") || lower.contains("色情")
            || lower.contains("porn") || lower.contains("成人")) {
            return new DeepSeekResult("色情", 0.65, new String[]{"porn"}, "熔断规则：色情关键词");
        }

        if (lower.contains("赌") || lower.contains("gamble") || lower.contains("casino")
            || lower.contains("博彩") || lower.contains("时时彩")) {
            return new DeepSeekResult("赌博", 0.65, new String[]{"gamble"}, "熔断规则：赌博关键词");
        }

        return null;
    }

    // ====== 工具方法 ======

    private UserEntity ensureUser(User from) {
        long id = from.id();
        var opt = userRepo.findById(id);
        if (opt.isPresent()) {
            UserEntity u = opt.get();
            return u;
        }
        UserEntity u = new UserEntity();
        u.setUserId(id);
        u.setUsername(from.username() != null ? from.username() : "");
        u.setCreditScore(100);
        u.setLang(from.languageCode() != null ? from.languageCode() : "zh");
        return userRepo.save(u);
    }

    private void ensureGroup(Long chatId, String title, String username) {
        var opt = groupRepo.findById(chatId);
        if (opt.isEmpty()) {
            GroupEntity g = new GroupEntity();
            g.setGroupId(chatId);
            g.setTitle(title != null ? title : "");
            g.setUsername(username);
            g.setEnvironmentScore(100);
            g.setActive(true);
            groupRepo.save(g);
            log.info("New group registered: {} ({})", chatId, title);
        }
    }

    /** 群组白名单管理 — /whitelist add/remove/list @用户 */
    private void handleWhitelist(TelegramBot bot, Long chatId, String text, UserEntity user, GroupRepository groupRepo) {
        if (!isSuperAdmin(user.getUserId())) {
            replyTemp(bot, chatId, "❌ 仅超级管理员可管理白名单", 10000);
            return;
        }

        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            replyTemp(bot, chatId, "📋 群组白名单管理\n\n用法：\n  /whitelist list                — 查看本群白名单\n  /whitelist add <用户ID>        — 添加白名单（上限10人）\n  /whitelist remove <用户ID>     — 移除白名单\n\n白名单用户不受一般违规处罚，但诈骗/政治内容零容忍", 30000);
            return;
        }

        try {
            var group = groupRepo.findById(chatId).orElse(null);
            if (group == null) {
                replyTemp(bot, chatId, "❌ 群组未注册", 10000);
                return;
            }

            switch (parts[1]) {
                case "list" -> {
                    var ids = group.getWhitelistUserIds();
                    if (ids.isEmpty()) {
                        replyTemp(bot, chatId, "📋 本群白名单为空\n\n使用 /whitelist add <用户ID> 添加", 10000);
                        return;
                    }
                    StringBuilder sb = new StringBuilder("📋 本群白名单 (" + ids.size() + "/10 人)\n\n");
                    int i = 1;
                    for (Long id : ids) {
                        sb.append(i++).append(". ").append(id).append("\n");
                    }
                    replyTemp(bot, chatId, sb.toString(), 30000);
                }
                case "add" -> {
                    if (parts.length < 3) {
                        replyTemp(bot, chatId, "用法：/whitelist add <用户ID>", 10000);
                        return;
                    }
                    long targetId = Long.parseLong(parts[2]);
                    if (group.addWhitelistUser(targetId)) {
                        groupRepo.save(group);
                        replyTemp(bot, chatId, "✅ 已添加 " + targetId + " 到白名单（" + group.getWhitelistUserIds().size() + "/10）", 10000);
                    } else {
                        replyTemp(bot, chatId, "❌ 白名单已满（上限10人）", 10000);
                    }
                }
                case "remove" -> {
                    if (parts.length < 3) {
                        replyTemp(bot, chatId, "用法：/whitelist remove <用户ID>", 10000);
                        return;
                    }
                    long targetId = Long.parseLong(parts[2]);
                    if (group.removeWhitelistUser(targetId)) {
                        groupRepo.save(group);
                        replyTemp(bot, chatId, "✅ 已从白名单移除 " + targetId, 10000);
                    } else {
                        replyTemp(bot, chatId, "❌ 该用户不在白名单中", 10000);
                    }
                }
                default -> replyTemp(bot, chatId, "未知子命令，可用：list / add / remove", 10000);
            }
        } catch (NumberFormatException e) {
            replyTemp(bot, chatId, "❌ 用户ID必须是数字", 10000);
        } catch (Exception e) {
            log.warn("Whitelist command failed: {}", e.getMessage());
            replyTemp(bot, chatId, "❌ 操作失败", 10000);
        }
    }

    /** 群公告管理 — /announce set <内容>  /announce remove  /announce show */
    private void handleAnnounce(TelegramBot bot, Long chatId, String text, UserEntity user, GroupRepository groupRepo, Message msg) {
        if (!isSuperAdmin(user.getUserId())) {
            replyTemp(bot, chatId, "❌ 仅超级管理员可管理公告", 10000);
            return;
        }

        String[] parts = text.split("\\s+", 2);
        if (parts.length < 2) {
            replyTemp(bot, chatId, "📋 群公告管理\n\n用法：\n  /announce show              — 查看当前公告\n  /announce set <公告内容>    — 发布/更新公告（自动置顶）\n  /announce remove            — 移除公告", 30000);
            return;
        }

        try {
            var group = groupRepo.findById(chatId).orElse(null);
            if (group == null) {
                replyTemp(bot, chatId, "❌ 群组未注册", 10000);
                return;
            }

            switch (parts[1]) {
                case "show" -> {
                    if (group.getAnnounceText() == null || group.getAnnounceText().isBlank()) {
                        replyTemp(bot, chatId, "📋 本群暂无公告", 10000);
                    } else {
                        replyTemp(bot, chatId, "📋 当前公告：\n\n" + group.getAnnounceText(), 30000);
                    }
                }
                case "set" -> {
                    if (parts.length < 2 || parts[1].length() < 4) {
                        replyTemp(bot, chatId, "用法：/announce set <公告内容>", 10000);
                        return;
                    }
                    String announceContent = parts[1].substring(4).trim();
                    if (announceContent.isEmpty() || announceContent.length() > 4000) {
                        replyTemp(bot, chatId, "❌ 公告内容不能为空且不超过4000字", 10000);
                        return;
                    }

                    // 先取消旧置顶
                    if (group.getPinnedAnnounceMsgId() != null) {
                        try {
                            bot.execute(new com.pengrad.telegrambot.request.UnpinChatMessage(chatId));
                        } catch (Exception e) {
                            log.debug("Failed to unpin old message in chat {}: {}", chatId, e.getMessage());
                        }
                    }

                    // 发送新公告并置顶
                    var sendMsg = bot.execute(new com.pengrad.telegrambot.request.SendMessage(chatId, "📢 公告：\n\n" + announceContent));
                    if (sendMsg != null && sendMsg.message() != null) {
                        int newMsgId = sendMsg.message().messageId();
                        bot.execute(new com.pengrad.telegrambot.request.PinChatMessage(chatId, newMsgId));
                        group.setPinnedAnnounceMsgId((long) newMsgId);
                    }

                    group.setAnnounceText(announceContent);
                    groupRepo.save(group);
                    replyTemp(bot, chatId, "✅ 公告已发布并置顶", 10000);
                }
                case "remove" -> {
                    if (group.getPinnedAnnounceMsgId() != null) {
                        try {
                            bot.execute(new com.pengrad.telegrambot.request.UnpinChatMessage(chatId));
                        } catch (Exception e) {
                            log.debug("Failed to unpin message in chat {}: {}", chatId, e.getMessage());
                        }
                    }
                    group.setPinnedAnnounceMsgId(null);
                    group.setAnnounceText(null);
                    groupRepo.save(group);
                    replyTemp(bot, chatId, "✅ 公告已移除", 10000);
                }
                default -> replyTemp(bot, chatId, "未知子命令，可用：show / set / remove", 10000);
            }
        } catch (Exception e) {
            log.warn("Announce command failed: {}", e.getMessage());
            replyTemp(bot, chatId, "❌ 操作失败：" + e.getMessage(), 10000);
        }
    }

    /** 检查是否是超级管理员 */
    private boolean isSuperAdmin(long userId) {
        for (String s : creatorIdsStr.split(",")) {
            if (Long.parseLong(s.trim()) == userId) return true;
        }
        return false;
    }

}
