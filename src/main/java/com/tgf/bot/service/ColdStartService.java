package com.tgf.bot.service;

import com.tgf.bot.model.UserEntity;
import com.tgf.bot.model.TicketEntity;
import com.tgf.bot.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * ColdStartService — 冷启动保护服务。
 * 
 * 系统上线前指定天数为冷启动期，所有自动化处罚转为人工确认工单，
 * 冷启动期结束后自动追扣 pending 处罚。
 * @since 1.0
 */
@Service
public class ColdStartService {

    private static final Logger log = LoggerFactory.getLogger(ColdStartService.class);

    @PersistenceContext
    private EntityManager em;

    private final UserRepository userRepo;
    private final CreditEngine creditEngine;
    private final FederalTrustService federalTrust;

    @Value("${system.cold-start:false}")
    private boolean isColdStart;

    @Value("${system.cold-start-days:7}")
    private int coldStartDays;

    /** 系统上线时间（从配置文件加载或数据库读取） */
    private LocalDateTime systemLaunchTime;

    public ColdStartService(UserRepository userRepo, CreditEngine creditEngine,
                            FederalTrustService federalTrust) {
        this.userRepo = userRepo;
        this.creditEngine = creditEngine;
        this.federalTrust = federalTrust;
    }

    public boolean isColdStartActive() {
        if (!isColdStart) return false;
        if (systemLaunchTime == null) return true;
        return LocalDateTime.now().isBefore(systemLaunchTime.plusDays(coldStartDays));
    }

    public int getColdStartDay() {
        if (systemLaunchTime == null) return 1;
        return (int) java.time.Duration.between(systemLaunchTime, LocalDateTime.now()).toDays() + 1;
    }

    public void setLaunchTime(LocalDateTime time) {
        this.systemLaunchTime = time;
    }

    /** 冷启动期处罚-生成待确认工单 */
    @Transactional
    public TicketEntity handlePenaltyInColdStart(long userId, String category, double confidence,
                                                  String reason, Long groupId, String messageText) {
        // 生成信用分记录（pending状态）
        UserEntity user = userRepo.findById(userId).orElse(null);
        if (user != null) {
            int penalty = switch (category) {
                case "色情" -> 30;
                case "赌博" -> 20;
                case "普通广告" -> 5;
                default -> 0;
            };
            if (penalty > 0) {
                creditEngine.apply(userId, -penalty, "punish",
                   "冷启动期-待确认: " + reason, "deepseek", null);
            }
        }

        // 生成待确认处罚工单
        TicketEntity ticket = new TicketEntity();
        ticket.setTicketType("death_review".equals(category) ? "death_review" : "pending_punish");
        ticket.setStatus("PENDING");
        ticket.setPriority("诈骗".equals(category) || "政治".equals(category) ? 2 : 1);
        ticket.setTargetUserId(userId);
        ticket.setContent(messageText != null ? messageText : reason);
        ticket.setRelatedGroupId(groupId);
        ticket.setColdStart(true);
        ticket.setSubmitterId(0L); // system
        ticket.setDeadlineAt(LocalDateTime.now().plusHours(24)); // 24hSLA

        em.persist(ticket);
        log.info("ColdStart: penalty pending ticket created for user={} category={}", userId, category);

        return ticket;
    }

    /** 冷启动期结束-自动追扣pending处罚 */
    @Transactional
    public void onColdStartEnd() {
        log.info("ColdStart period ended. Processing pending penalties...");

        // 查询所有冷启动期pending处罚工单（超时自动执行）
        var pendingTickets = em.createQuery(
            "FROM TicketEntity t WHERE t.coldStart = true AND t.status = 'PENDING' " +
            "AND t.ticketType = 'pending_punish'",
            TicketEntity.class).getResultList();

        for (var t : pendingTickets) {
            t.setStatus("PASSED");
            t.setReviewedAt(LocalDateTime.now());
            t.setReviewComment("冷启动期结束自动执行");

            // 检查追扣后是否需全局禁言
            UserEntity user = userRepo.findById(t.getTargetUserId()).orElse(null);
            if (user != null && federalTrust.shouldMute(user.getUserId())) {
                log.warn("ColdStart: user={} credit={} -> global mute after retroactive penalty",
                    user.getUserId(), user.getCreditScore());
                t.setReviewComment(t.getReviewComment() + " [触发全局禁言]");
            }
        }

        log.info("ColdStart: processed {} pending penalty tickets", pendingTickets.size());
    }
}
