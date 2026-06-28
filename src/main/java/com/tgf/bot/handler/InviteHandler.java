package com.tgf.bot.handler;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import com.tgf.bot.repository.UserRepository;
import com.tgf.bot.service.CreditEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * InviteHandler — 邀请处理器。
 * 
 * 处理 /start invite_xxx 格式的邀请链接，验证邀请码、
 * 注册新用户并发放邀请奖励。
 * @since 1.0
 */
@Component
public class InviteHandler implements BotHandler {

    private static final Logger log = LoggerFactory.getLogger(InviteHandler.class);

    private final UserRepository userRepo;
    private final CreditEngine creditEngine;

    public InviteHandler(UserRepository userRepo, CreditEngine creditEngine) {
        this.userRepo = userRepo;
        this.creditEngine = creditEngine;
    }

    @Override
    public boolean canHandle(Update update) {
        Message msg = update.message();
        if (msg == null || msg.text() == null) return false;
        String text = msg.text().trim();
        // 仅私聊 /start invite_xxx
        if (!text.startsWith("/start ")) return false;
        if (msg.chat().type() != com.pengrad.telegrambot.model.Chat.Type.Private) return false;

        String param = text.substring(7).trim();
        return param.startsWith("invite_");
    }

    @Override
    public void handle(Update update, TelegramBot bot) {
        Message msg = update.message();
        String text = msg.text().trim();
        Long chatId = msg.chat().id();
        User from = msg.from();
        if (from == null) return;

        String inviteCode = text.substring(7).trim().replace("invite_", "");

        // 查找邀请人
        var inviterOpt = userRepo.findByInviteCode(inviteCode);
        if (inviterOpt.isEmpty()) {
            reply(bot, chatId, "❌ 邀请链接无效或已过期");
            return;
        }

        var inviter = inviterOpt.get();
        long inviterId = inviter.getUserId();

        // 检查自邀请
        if (inviterId == from.id()) {
            reply(bot, chatId, "⚠️ 不能使用自己的邀请链接");
            return;
        }

        // 检查每日邀请码限制
        if (inviter.getInviteCount() >= 10) {
            reply(bot, chatId, "❌ 邀请人今日邀请码已达上限（10个）");
            return;
        }

        // 注册新用户（如果尚未注册）
        var newUser = userRepo.findById(from.id());
        if (newUser.isPresent()) {
            reply(bot, chatId, "⚠️ 你已经是系统用户，无法通过邀请链接注册");
            return;
        }

        var user = new com.tgf.bot.model.UserEntity();
        user.setUserId(from.id());
        user.setUsername(from.username() != null ? from.username() : "");
        user.setCreditScore(100);
        user.setLang(from.languageCode() != null ? from.languageCode() : "zh");
        user.setInviteCode(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        userRepo.save(user);

        // 邀请奖励：邀请人+2分
        creditEngine.apply(inviterId, 2, "reward", "邀请新用户", "system", null);

        // 邀请计数
        inviter.setInviteCount(inviter.getInviteCount() + 1);
        userRepo.save(inviter);

        reply(bot, chatId, "✅ 欢迎加入！邀请人 @" + (inviter.getUsername() != null ? inviter.getUsername() : String.valueOf(inviterId))
            + " 已获得 +2 信用分奖励。\n\n"
            + "使用 /me 查看你的个人档案。");

        log.info("User registered via invite: new={} inviter={} code={}", from.id(), inviterId, inviteCode);
    }
}
