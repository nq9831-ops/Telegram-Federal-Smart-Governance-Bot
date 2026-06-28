package com.tgf.bot.handler;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.request.DeleteMessage;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.model.request.ParseMode;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * BotHandler — Bot 命令处理器接口。
 * 
 * 责任链模式的核心接口，定义处理器通用方法与默认工具方法。
 * @since 1.0
 */
public interface BotHandler {

    /** 共享的定时删除线程池 — 避免每次 replyTemp 创建新线程 */
    ScheduledExecutorService TEMP_MSG_SCHEDULER = Executors.newScheduledThreadPool(1, r -> {
        Thread t = Executors.defaultThreadFactory().newThread(r);
        t.setDaemon(true);
        t.setName("temp-msg-cleaner");
        return t;
    });

    boolean canHandle(Update update);

    void handle(Update update, TelegramBot bot);

    default boolean isCreator(User user, long creatorId) {
        return user != null && user.id().longValue() == creatorId;
    }

    default void reply(TelegramBot bot, Long chatId, String text) {
        bot.execute(new SendMessage(chatId, text));
    }

    default void replyHtml(TelegramBot bot, Long chatId, String html) {
        bot.execute(new SendMessage(chatId, html).parseMode(ParseMode.HTML));
    }

    default void replyTemp(TelegramBot bot, Long chatId, String text, int deleteAfterMs) {
        var msg = bot.execute(new SendMessage(chatId, text));
        if (msg.isOk() && deleteAfterMs > 0) {
            int msgId = msg.message().messageId();
            TEMP_MSG_SCHEDULER.schedule(() -> {
                try { bot.execute(new DeleteMessage(chatId, msgId)); } catch (Exception e) {
                        Logger.getLogger("BotHandler").log(Level.WARNING, "Failed to delete temp msg: {0}", e.getMessage());
                    }
            }, deleteAfterMs, TimeUnit.MILLISECONDS);
        }
    }
}
