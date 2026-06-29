package com.tgf.bot.handler;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.ChatMemberUpdated;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import com.tgf.bot.model.UserLeaveEntity;
import com.tgf.bot.repository.GroupRepository;
import com.tgf.bot.repository.UserRepository;
import com.tgf.bot.service.CaptchaService;
import com.tgf.bot.service.CreditEngine;
import com.tgf.bot.service.TicketService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MemberUpdateHandler — 成员变更处理器。
 * 
 * 处理群组成员进出事件：机器人被踢检测、用户进出风控联动、
 * 群组跳蚤检测、踢出次数告警等。
 * @since 1.0
 */
@Component
public class MemberUpdateHandler implements BotHandler {

    private static final Logger log = LoggerFactory.getLogger(MemberUpdateHandler.class);

    @PersistenceContext
    private EntityManager em;

    private final UserRepository userRepo;
    private final GroupRepository groupRepo;
    private final CreditEngine creditEngine;
    private final CaptchaService captchaService;
    private final TicketService ticketService;

    @Value("${bot.creator:0}")
    private String creatorIdsStr;

    public MemberUpdateHandler(UserRepository userRepo, GroupRepository groupRepo,
                                CreditEngine creditEngine, CaptchaService captchaService,
                                TicketService ticketService) {
        this.userRepo = userRepo;
        this.groupRepo = groupRepo;
        this.creditEngine = creditEngine;
        this.captchaService = captchaService;
        this.ticketService = ticketService;
    }

    @Override
    public boolean canHandle(Update update) {
        // my_chat_member 更新 - 机器人的群成员状态变更
        if (update.myChatMember() != null) return true;
        // chat_member 更新 - 普通用户的成员变更
        return update.chatMember() != null;
    }

    @Override
    public void handle(Update update, TelegramBot bot) {
        // 处理 my_chat_member（机器人自己被踢/被加入）
        if (update.myChatMember() != null) {
            handleMyChatMember(update.myChatMember(), bot);
        }
        // 处理 chat_member（普通用户进出）
        if (update.chatMember() != null) {
            handleChatMember(update.chatMember(), bot);
        }
    }

    private void handleMyChatMember(ChatMemberUpdated member, TelegramBot bot) {
        long chatId = member.chat().id();
        var newStatus = member.newChatMember().status();

        // 机器人被加入群组 — 自动初始化/重新激活群组记录
        if (newStatus == com.pengrad.telegrambot.model.ChatMember.Status.member
            || newStatus == com.pengrad.telegrambot.model.ChatMember.Status.administrator) {
            var existing = groupRepo.findById(chatId);
            if (existing.isEmpty()) {
                var group = new com.tgf.bot.model.GroupEntity();
                group.setGroupId(chatId);
                group.setTitle(member.chat().title() != null ? member.chat().title() : String.valueOf(chatId));
                group.setUsername(member.chat().username());
                group.setActive(true);
                groupRepo.save(group);
                log.info("Bot added to new group: {} ({})", chatId, group.getTitle());
            } else {
                var g = existing.get();
                g.setActive(true);
                g.setInvalidReason(null);
                g.setInvalidatedAt(null);
                groupRepo.save(g);
                log.info("Bot re-added to group: {} ({})", chatId, g.getTitle());
            }
            return;
        }

        // 机器人被踢出群组
        if (newStatus == com.pengrad.telegrambot.model.ChatMember.Status.kicked
            || newStatus == com.pengrad.telegrambot.model.ChatMember.Status.left) {
            // 被踢出
            var group = groupRepo.findById(chatId);
            group.ifPresent(g -> {
                g.setActive(false);
                g.setInvalidReason("机器人被踢出群组");
                g.setInvalidatedAt(LocalDateTime.now());
                groupRepo.save(g);
                log.warn("Bot kicked from group {} ({})", chatId, g.getTitle());
            });

            // 通知超级管理员
            for (String s : creatorIdsStr.split(",")) {
                try {
                    long adminId = Long.parseLong(s.trim());
                    bot.execute(new com.pengrad.telegrambot.request.SendMessage(adminId,
                        "⚠️ 机器人被从群组 " + chatId + " 踢出\n"
                        + "群组名称: " + member.chat().title() + "\n"
                        + "时间: " + LocalDateTime.now()));
                } catch (Exception e) {
                    log.warn("Failed to notify admin {} about kick: {}", s, e.getMessage());
                }
            }
        }
    }

    private void handleChatMember(ChatMemberUpdated member, TelegramBot bot) {
        long chatId = member.chat().id();
        User user = member.newChatMember().user();
        if (user == null) return;

        var newStatus = member.newChatMember().status();
        var oldStatus = member.oldChatMember().status();

        Long userId = user.id();

        // 用户加入群组
        if ((oldStatus == com.pengrad.telegrambot.model.ChatMember.Status.left
            || oldStatus == com.pengrad.telegrambot.model.ChatMember.Status.kicked)
            && newStatus == com.pengrad.telegrambot.model.ChatMember.Status.member) {
            handleUserJoined(bot, chatId, userId, user);
        }

        // 用户退出或被踢出
        if ((oldStatus == com.pengrad.telegrambot.model.ChatMember.Status.member
            || oldStatus == com.pengrad.telegrambot.model.ChatMember.Status.administrator)
            && (newStatus == com.pengrad.telegrambot.model.ChatMember.Status.left
            || newStatus == com.pengrad.telegrambot.model.ChatMember.Status.kicked)) {
            handleUserLeft(bot, chatId, userId, newStatus);
        }
    }

    private void handleUserJoined(TelegramBot bot, Long chatId, Long userId, User user) {
        var userEntity = userRepo.findById(userId).orElse(null);

        // 入群欢迎
        String username = user.username() != null ? "@" + user.username() : "用户" + userId;
        try {
            bot.execute(new com.pengrad.telegrambot.request.SendMessage(chatId,
                "🎉 欢迎 " + username + " 加入群组！\n" +
                "📌 请遵守群规，违规将被处罚。\n" +
                "📊 发送 /me 查看信用分，/help 查看帮助"));
        } catch (Exception e) {
            log.debug("Failed to send welcome: {}", e.getMessage());
        }

        // 入群验证码
        try {
            var arith = captchaService.generateArithmetic();
            bot.execute(new com.pengrad.telegrambot.request.SendMessage(userId,
                "🔐 请在 5 分钟内回复以下算术验证码完成验证：\n\n" + arith.question() +
                "\n\n回复格式：/captcha_answer " + arith.captchaId() + " <你的答案>"));
        } catch (Exception e) {
            log.debug("Failed to send captcha: {}", e.getMessage());
        }

        if (userEntity == null) {
            try {
                bot.execute(new com.pengrad.telegrambot.request.SendMessage(userId,
                    "📝 请发送 /start 完成注册，获取完整功能。"));
            } catch (Exception e) {
                log.debug("Failed to send registration reminder to user {}: {}", userId, e.getMessage());
            }
            return;
        }

        int credit = userEntity.getCreditScore();

        if (credit >= 60) {
            return;
        }

        if (credit >= 50) {
            // 仅私聊提醒
            try {
                bot.execute(new com.pengrad.telegrambot.request.SendMessage(userId,
                    "⚠️ 你当前的信用分较低（" + credit + "），请在群组中注意言行。"));
            } catch (Exception e) {
                log.debug("Failed to send credit warning to user {}: {}", userId, e.getMessage());
            }
            return;
        }

        // 信用分 < 50，群内公开警告 + 私聊处罚书
        try {
            bot.execute(new com.pengrad.telegrambot.request.SendMessage(chatId,
                "⚠️ 注意：用户 @" + (user.username() != null ? user.username() : String.valueOf(userId))
                + " 信用分较低（" + credit + "），请注意其言论。"));
        } catch (Exception e) {
            log.debug("Failed to send credit warning: {}", e.getMessage());
        }
    }

    private void handleUserLeft(TelegramBot bot, Long chatId, Long userId,
                                com.pengrad.telegrambot.model.ChatMember.Status newStatus) {
        var userEntity = userRepo.findById(userId).orElse(null);
        int creditScore = userEntity != null ? userEntity.getCreditScore() : 100;

        // 记录退群事件
        var leave = new UserLeaveEntity();
        leave.setUserId(userId);
        leave.setGroupId(chatId);
        leave.setLeftType(newStatus == com.pengrad.telegrambot.model.ChatMember.Status.kicked ? "被踢出" : "主动退出");
        leave.setCurrentCreditScore(creditScore);
        em.persist(leave);

        // 风控联动（规格书32.3）
        // 统计7天内进出群组次数
        Long count = em.createQuery(
            "SELECT COUNT(l) FROM UserLeaveEntity l WHERE l.userId = :uid AND l.leftAt >= :since",
            Long.class)
            .setParameter("uid", userId)
            .setParameter("since", LocalDateTime.now().minusDays(7))
            .getSingleResult();

        if (count >= 5) {
            // 标记为群组跳蚤
            if (userEntity != null && !userEntity.isGroupJumper()) {
                userEntity.setGroupJumper(true);
                // 扣10分
                creditEngine.apply(userId, -10, "punish", "7天内进出5个以上群组", "system", null);
                userRepo.save(userEntity);
                log.warn("Group jumper detected: user={} groups in 7d={}", userId, count);
            }
        }

        // 30天内被3个以上群组踢出 → 告警工单
        if ("被踢出".equals(leave.getLeftType())) {
            Long kickedCount = em.createQuery(
                "SELECT COUNT(l) FROM UserLeaveEntity l WHERE l.userId = :uid AND l.leftType = '被踢出' AND l.leftAt >= :since",
                Long.class)
                .setParameter("uid", userId)
                .setParameter("since", LocalDateTime.now().minusDays(30))
                .getSingleResult();

            if (kickedCount >= 3) {
                log.warn("User kicked from 3+ groups in 30d: user={}", userId);
                // 生成告警工单
                try {
                    var userForTicket = userEntity;
                    if (userForTicket != null) {
                        ticketService.createTicket("contact_admin", userForTicket,
                            "用户30天内被3个以上群组踢出，建议关注", null);
                    }
                } catch (Exception e) {
                    log.warn("Failed to create alert ticket for user {}: {}", userId, e.getMessage());
                }
            }
        }

        log.info("User left group: user={} group={} type={} credit={}",
            userId, chatId, leave.getLeftType(), creditScore);
    }
}
