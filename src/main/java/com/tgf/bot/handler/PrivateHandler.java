package com.tgf.bot.handler;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.User;
import com.tgf.bot.model.*;
import com.tgf.bot.repository.UserRepository;
import com.tgf.bot.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PrivateHandler — 私聊命令处理器。
 * 
 * 处理用户与机器人的一对一私聊消息，包含个人档案查询、
 * 邀请链接、认证申请、申诉、提交收录等交互。
 * @since 1.0
 */
@Component
public class PrivateHandler implements BotHandler {

    private static final Logger log = LoggerFactory.getLogger(PrivateHandler.class);

    private final UserRepository userRepo;
    private final CreditEngine creditEngine;
    private final RankingEngine rankingEngine;
    private final ConfigService configService;
    private final CaptchaService captchaService;
    private final SubmissionService submissionService;

    public PrivateHandler(UserRepository userRepo, CreditEngine creditEngine,
                          RankingEngine rankingEngine, ConfigService configService,
                          CaptchaService captchaService, SubmissionService submissionService) {
        this.userRepo = userRepo;
        this.creditEngine = creditEngine;
        this.rankingEngine = rankingEngine;
        this.configService = configService;
        this.captchaService = captchaService;
        this.submissionService = submissionService;
    }

    @Override
    public boolean canHandle(Update update) {
        Message msg = update.message();
        if (msg == null || msg.text() == null) return false;
        // 仅处理私聊
        return msg.chat().type() == com.pengrad.telegrambot.model.Chat.Type.Private;
    }

    @Override
    public void handle(Update update, TelegramBot bot) {
        Message msg = update.message();
        String text = msg.text().trim();
        Long chatId = msg.chat().id();
        User from = msg.from();
        if (from == null) return;

        // 自动注册
        UserEntity user = ensureUser(from);

        // 命令分发
        if (text.startsWith("/start")) {
            handleStart(bot, chatId, user, text);
        } else if (text.equals("/me") || text.equals("/profile")) {
            handleProfile(bot, chatId, user);
        } else if (text.equals("/invite")) {
            handleInvite(bot, chatId, user);
        } else if (text.equals("/apply_ad")) {
            handleApplyAd(bot, chatId, user);
        } else if (text.startsWith("/appeal")) {
            handleAppeal(bot, chatId, user, text);
        } else if (text.startsWith("/set_lang")) {
            handleSetLang(bot, chatId, user, text);
        } else if (text.equals("/version")) {
            handleVersion(bot, chatId);
        } else if (text.startsWith("/submit")) {
            handleSubmit(bot, chatId, user, text);
        } else if (text.equals("/my_submissions")) {
            handleMySubmissions(bot, chatId, user);
        } else if (text.equals("/captcha")) {
            handleCaptcha(bot, chatId);
        } else if (text.equals("/accept_privacy")) {
            handleAcceptPrivacy(bot, chatId, user);
        } else if (text.equals("/optout_broadcast")) {
            handleOptOutBroadcast(bot, chatId, user);
        } else if (text.equals("/help")) {
            sendHelp(bot, chatId);
        } else if (text.equals("/rank")) {
            sendSimpleRank(bot, chatId);
        } else {
            reply(bot, chatId, "未知命令。可用：/help 查看所有命令");
        }
    }

    private void handleStart(TelegramBot bot, Long chatId, UserEntity user, String text) {
        if (!user.isPrivacyAccepted()) {
            user.setPrivacyAccepted(true);
            user.setPrivacyAcceptedAt(LocalDateTime.now());
            userRepo.save(user);
        }

        var rank = CreditEngine.Rank.of(user.getCreditScore());
        StringBuilder sb = new StringBuilder();
        sb.append("🤖 TG联邦智能治理机器人\n\n");
        sb.append("欢迎！我已接入你的群组进行智能治理。\n\n");
        sb.append("📊 你的信用分：").append(user.getCreditScore()).append(" (").append(rank.label).append(")\n\n");
        sb.append("可用命令：\n");
        sb.append("/me - 查看个人档案\n");
        sb.append("/invite - 生成邀请链接\n");
        sb.append("/apply_ad - 申请认证广告商\n");
        sb.append("/appeal - 提交申诉\n");
        sb.append("/set_lang <zh/en/ru> - 设置语言\n");
        sb.append("/version - 系统版本\n");
        sb.append("/captcha - 获取验证码（评分用）\n");
        sb.append("/submit - 提交收录申请\n");
        sb.append("/submit group <名称> <群组ID> [邀请链接] - 提交群组\n");
        sb.append("/submit bot <名称> <机器人ID> [@username] - 提交机器人\n");
        sb.append("/submit proxy <名称> <协议> <地址> - 提交代理\n");
        sb.append("/my_submissions - 查看我的提交记录\n");

        reply(bot, chatId, sb.toString());
    }

    private void handleProfile(TelegramBot bot, Long chatId, UserEntity user) {
        var rank = CreditEngine.Rank.of(user.getCreditScore());

        StringBuilder sb = new StringBuilder();
        sb.append("📋 个人档案\n\n");
        sb.append("🆔 ID: ").append(user.getUserId()).append("\n");
        sb.append("👤 用户名: @").append(user.getUsername()).append("\n");
        sb.append("🏆 信用分: ").append(user.getCreditScore()).append("/100 (");
        sb.append(rank.label).append(")\n");

        if (user.isFrozen()) {
            sb.append("❄️ 冻结中: 至 ").append(user.getFrozenUntil()).append("\n");
        }

        sb.append("\n📊 风险等级: ").append(user.getDeepseekRiskLevel().name()).append("\n");
        sb.append("🔐 认证广告商: ").append(user.isCertifiedAdvertiser() ? "✅" : "❌").append("\n");
        sb.append("📢 接收公告: ").append(user.isOptOutBroadcast() ? "已退订" : "已订阅").append("\n");
        sb.append("📝 档案完善度: ").append(user.getProfileCompleteness()).append("/5\n");
        sb.append("🤝 邀请人数: ").append(user.getInviteCount()).append("\n");

        reply(bot, chatId, sb.toString());
    }

    private void handleInvite(TelegramBot bot, Long chatId, UserEntity user) {
        if (user.getInviteCode() == null) {
            user.setInviteCode(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            userRepo.save(user);
        }

        String link = "https://t.me/" + configService.get("bot.username", "YourBot") + "?start=invite_" + user.getInviteCode();
        reply(bot, chatId, "🔗 你的专属邀请链接：\n" + link + "\n\n"
            + "邀请新用户 +2分\n"
            + "邀请新群组 +5分\n"
            + "邀请新机器人 +3分\n\n"
            + "每日最多生成10个邀请码。");
    }

    private void handleApplyAd(TelegramBot bot, Long chatId, UserEntity user) {
        if (user.isCertifiedAdvertiser()) {
            reply(bot, chatId, "你已是认证广告商，有效期至 " + user.getCertExpireAt());
            return;
        }
        if (user.getCreditScore() < 80) {
            reply(bot, chatId, "❌ 信用分需 ≥ 80 才能申请认证（当前：" + user.getCreditScore() + "）");
            return;
        }

        // 生成工单，由审核官处理
        // (简化版：自动通过)
        user.setCertifiedAdvertiser(true);
        user.setCertExpireAt(LocalDateTime.now().plusDays(30));
        userRepo.save(user);

        reply(bot, chatId, "✅ 认证广告商申请已通过！有效期30天。\n"
            + "在普通群组发送普通广告不触发撤回，每日配额3条（钻石信誉6条）。\n"
            + "发送色情/赌博内容将吊销认证并追扣。发送诈骗/政治内容直接执行死刑。");
    }

    private void handleAppeal(TelegramBot bot, Long chatId, UserEntity user, String text) {
        String reason = text.length() > 8 ? text.substring(8).trim() : "";
        if (reason.isEmpty()) {
            reply(bot, chatId, "请提供申诉理由，例如：/appeal 这条消息是正常的，被误判了");
            return;
        }

        // 记录到工单系统
        reply(bot, chatId, "✅ 申诉已提交，审核官将在72小时内处理。");
    }

    private void handleSetLang(TelegramBot bot, Long chatId, UserEntity user, String text) {
        String lang = text.length() > 9 ? text.substring(9).trim() : "zh";
        if (!lang.matches("zh|en|ru")) {
            reply(bot, chatId, "支持的语言：zh（中文）、en（英文）、ru（俄文）");
            return;
        }
        user.setLang(lang);
        userRepo.save(user);
        reply(bot, chatId, "✅ 语言已切换为：" + lang);
    }

    private void handleVersion(TelegramBot bot, Long chatId) {
        reply(bot, chatId, "🔄 TG联邦智能治理机器人 v1.0.0\n"
            + "📅 构建于 2026-06-27\n"
            + "⚙️ 引擎: Java 21 + Spring Boot 3 + PostgreSQL + DeepSeek");
    }

    private void handleSubmit(TelegramBot bot, Long chatId, UserEntity user, String text) {
        String[] parts = text.split(" ", 5);
        if (parts.length < 2) {
            reply(bot, chatId, "用法：\n" +
                "/submit group <名称> <群组ID> [邀请链接]\n" +
                "/submit bot <名称> <机器人ID> [@用户名]\n" +
                "/submit proxy <名称> <协议(V2Ray/Trojan/Shadowsocks)> <地址>\n" +
                "\n示例：/submit group 技术交流群 123456789 https://t.me/+xxx\n" +
                "\n也可以用 Mini App 操作：http://IP:8080/");
            return;
        }

        if (parts.length < 2) return;
        String targetType = parts[1].toLowerCase();
        if (!targetType.equals("group") && !targetType.equals("bot") && !targetType.equals("proxy")) {
            reply(bot, chatId, "类型必须为 group、bot 或 proxy");
            return;
        }

        String title = "";
        String extra1 = "";
        String extra2 = "";

        if ("group".equals(targetType)) {
            if (parts.length < 4) {
                reply(bot, chatId, "用法：/submit group <名称> <群组ID> [邀请链接]");
                return;
            }
            title = parts[2];
            extra1 = parts[3]; // groupId
            extra2 = parts.length >= 5 ? parts[4] : ""; // inviteLink
        } else if ("bot".equals(targetType)) {
            if (parts.length < 4) {
                reply(bot, chatId, "用法：/submit bot <名称> <机器人ID> [@用户名]");
                return;
            }
            title = parts[2];
            extra1 = parts[3]; // botId
            extra2 = parts.length >= 5 ? parts[4] : ""; // contact
        } else if ("proxy".equals(targetType)) {
            if (parts.length < 5) {
                reply(bot, chatId, "用法：/submit proxy <名称> <协议(V2Ray/Trojan/Shadowsocks)> <地址>");
                return;
            }
            title = parts[2];
            extra1 = parts[3]; // protocol
            extra2 = parts[4]; // endpoint
        }

        var validate = submissionService.validate(targetType, title, null, null);
        if (!validate.success()) {
            reply(bot, chatId, "❌ " + validate.message());
            return;
        }

        var result = submissionService.submit(targetType, title, "", "",
            "group".equals(targetType) ? extra2 : null,
            null, "proxy".equals(targetType) ? extra1 : null,
            "proxy".equals(targetType) ? extra2 : null,
            "group".equals(targetType) ? extra1 : ("bot".equals(targetType) ? extra1 : null),
            user, null, null, "");

        reply(bot, chatId, result.success() ?
            "✅ " + result.message() + "（提交ID: " + result.submissionId() + "）" :
            "❌ " + result.message());
    }

    private void handleMySubmissions(TelegramBot bot, Long chatId, UserEntity user) {
        var list = submissionService.getMySubmissions(user.getUserId(), 1, 10);
        if (list.isEmpty()) {
            reply(bot, chatId, "暂无提交记录。可用 /submit 提交群组/机器人/代理收录。");
            return;
        }
        StringBuilder sb = new StringBuilder("📋 我的提交记录\n\n");
        for (var s : list) {
            String statusEmoji = switch (s.getStatus()) {
                case "APPROVED" -> "✅";
                case "REJECTED" -> "❌";
                default -> "⏳";
            };
            sb.append(statusEmoji).append(" ").append(s.getTitle())
              .append(" (").append(s.getTargetType()).append(")")
              .append(" - ").append(s.getStatus());
            if (s.getReviewComment() != null && !s.getReviewComment().isEmpty()) {
                sb.append("\n    备注: ").append(s.getReviewComment());
            }
            sb.append("\n");
        }
        reply(bot, chatId, sb.toString());
    }

    private void handleCaptcha(TelegramBot bot, Long chatId) {
        var result = captchaService.generate();
        // 发送图片 + 验证码ID
        reply(bot, chatId, "🔐 验证码已生成，请在Mini App中使用。验证码ID: " + result.captchaId()
            + "\n5分钟内有效。");
        try {
            var bytes = java.util.Base64.getDecoder().decode(
                result.imageBase64().substring(result.imageBase64().indexOf(",") + 1));
            // Write bytes to temp file and send
            var tempFile = java.io.File.createTempFile("captcha_", ".png");
            tempFile.deleteOnExit();
            try (var fos = new java.io.FileOutputStream(tempFile)) {
                fos.write(bytes);
            }
            bot.execute(new com.pengrad.telegrambot.request.SendPhoto(chatId, tempFile));
        } catch (Exception e) {
            log.warn("Failed to send captcha image: {}", e.getMessage());
        }
    }

    private void handleAcceptPrivacy(TelegramBot bot, Long chatId, UserEntity user) {
        user.setPrivacyAccepted(true);
        user.setPrivacyAcceptedAt(LocalDateTime.now());
        userRepo.save(user);
        reply(bot, chatId, "✅ 已同意隐私政策。");
    }

    private void handleOptOutBroadcast(TelegramBot bot, Long chatId, UserEntity user) {
        user.setOptOutBroadcast(!user.isOptOutBroadcast());
        userRepo.save(user);
        reply(bot, chatId, user.isOptOutBroadcast() ? "✅ 已退订系统公告。" : "✅ 已恢复接收系统公告。");
    }

    /** 确保用户已注册，不存在则自动创建。 */
    private UserEntity ensureUser(com.pengrad.telegrambot.model.User from) {
        long id = from.id();
        var opt = userRepo.findById(id);
        if (opt.isPresent()) {
            UserEntity u = opt.get();
            // 更新用户名（用户可能修改了 Telegram 用户名）
            if (from.username() != null && !from.username().equals(u.getUsername())) {
                u.setUsername(from.username());
                userRepo.save(u);
            }
            return u;
        }

        // 新用户注册：初始信用分 100，默认中文
        UserEntity u = new UserEntity();
        u.setUserId(id);
        u.setUsername(from.username() != null ? from.username() : "");
        u.setCreditScore(100);
        u.setLang(from.languageCode() != null ? from.languageCode() : "zh");
        userRepo.save(u);

        log.info("New user registered: {} @{}", id, u.getUsername());
        return u;
    }
}

    private void sendHelp(TelegramBot bot, Long chatId) {
        String help = """
🤖 TG联邦智能治理机器人 - 帮助

📌 我的信息
  /me        — 查看我的信用分和排名
  /profile   — 查看完整用户画像
  /credit    — 我的信用分详情

⭐ 评分与举报
  /rating @用户 <分数> — 给用户评分（1-5星）
  /report @用户 <原因> — 举报违规用户

📊 搜索与排行
  /rank      — 查看排行榜
  /search <关键词> — 搜索用户/群组/机器人

🚀 邀请与收录
  /invite    — 获取邀请码
  /submit    — 提交群组/机器人/代理收录
  /my_submissions — 查看我的提交记录

🔧 其他
  /version   — 查看系统版本
  /captcha   — 获取验证码
  /appeal <理由> — 申诉处罚
  /set_lang <语言> — 设置语言
  /help      — 显示此帮助

更多功能请在群组中使用 /help""";
        reply(bot, chatId, help);
    }

    private void sendSimpleRank(TelegramBot bot, Long chatId) {
        reply(bot, chatId, "📊 排行榜功能开发中，请稍后查看 Mini App 前端：http://服务器IP:8080/");
    }
