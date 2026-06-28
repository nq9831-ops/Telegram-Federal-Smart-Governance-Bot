package com.tgf.bot.service;

import com.tgf.bot.model.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * SubmissionService — 收录提交服务。
 * 
 * 用户通过命令或 Mini App 提交群组/机器人/代理信息申请纳入联盟索引，
 * 管理员审核通过后自动创建对应收录实体。
 * @since 1.0
 */
@Service
public class SubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

    @PersistenceContext
    private EntityManager em;

    private final TicketService ticketService;
    private final CaptchaService captchaService;

    @Value("${submission.cooldown-minutes:60}")
    private int cooldownMinutes;

    @Value("${submission.daily-limit:5}")
    private int dailyLimit;

    @Value("${submission.require-captcha:true}")
    private boolean requireCaptcha;

    public SubmissionService(TicketService ticketService, CaptchaService captchaService) {
        this.ticketService = ticketService;
        this.captchaService = captchaService;
    }

    public record SubmitResult(boolean success, String message, Long submissionId) {}

    /**
     * 验证提交参数完整性
     */
    public SubmitResult validate(String targetType, String title, String captchaId, String captchaInput) {
        if (targetType == null || (!targetType.equals("group") && !targetType.equals("bot") && !targetType.equals("proxy"))) {
            return new SubmitResult(false, "提交类型必须为 group/bot/proxy", null);
        }
        if (title == null || title.trim().isEmpty()) {
            return new SubmitResult(false, "名称不能为空", null);
        }
        if (title.length() > 120) {
            return new SubmitResult(false, "名称不能超过120个字符", null);
        }
        return new SubmitResult(true, "", null);
    }

    /**
     * 用户提交收录申请
     */
    @Transactional
    public SubmitResult submit(String targetType, String title, String description,
                               String contact, String inviteLink, String groupLabel,
                               String protocol, String endpoint, String targetId,
                               UserEntity submitter, String captchaId, String captchaInput,
                               String ip) {
        // 验证码
        if (requireCaptcha && captchaId != null && captchaInput != null) {
            if (!captchaService.verify(captchaId, captchaInput)) {
                return new SubmitResult(false, "验证码错误或已过期", null);
            }
        }

        // 频率限制
        long now = System.currentTimeMillis();
        LocalDateTime since = LocalDateTime.now().minusMinutes(cooldownMinutes);
        long recentCount = em.createQuery(
            "SELECT COUNT(s) FROM SubmissionEntity s WHERE s.submitterId = :uid AND s.createdAt > :since",
            Long.class)
            .setParameter("uid", submitter.getUserId())
            .setParameter("since", since)
            .getSingleResult();
        if (recentCount > 0) {
            return new SubmitResult(false, "提交太频繁，每次提交间隔 " + cooldownMinutes + " 分钟", null);
        }

        // 每日限制
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        long todayCount = em.createQuery(
            "SELECT COUNT(s) FROM SubmissionEntity s WHERE s.submitterId = :uid AND s.createdAt > :today",
            Long.class)
            .setParameter("uid", submitter.getUserId())
            .setParameter("today", todayStart)
            .getSingleResult();
        if (todayCount >= dailyLimit) {
            return new SubmitResult(false, "今日提交已达上限（" + dailyLimit + "次）", null);
        }

        // 检查信用分门槛
        if (submitter.getCreditScore() < 30) {
            return new SubmitResult(false, "信用分需 ≥ 30 才能提交收录", null);
        }

        // 重复检查：同类型同名/同链接
        if (targetId != null && !targetId.isEmpty()) {
            long dup = em.createQuery(
                "SELECT COUNT(s) FROM SubmissionEntity s WHERE s.targetType = :type AND s.targetId = :tid AND s.status IN ('PENDING','APPROVED')",
                Long.class)
                .setParameter("type", targetType)
                .setParameter("tid", targetId)
                .getSingleResult();
            if (dup > 0) {
                return new SubmitResult(false, "该目标已有提交记录，请勿重复提交", null);
            }
        }

        // 构建提交记录
        SubmissionEntity sub = new SubmissionEntity();
        sub.setTargetType(targetType);
        sub.setTitle(title.trim());
        sub.setDescription(description != null ? description.trim() : "");
        sub.setContact(contact != null ? contact.trim() : "");
        sub.setTargetId(targetId != null ? targetId.trim() : "");
        sub.setSubmitterId(submitter.getUserId());
        sub.setSubmitterUsername(submitter.getUsername());
        sub.setStatus("PENDING");

        if ("group".equals(targetType)) {
            sub.setInviteLink(inviteLink);
            sub.setGroupLabel(groupLabel);
        } else if ("proxy".equals(targetType)) {
            sub.setProtocol(protocol);
            sub.setEndpoint(endpoint);
        }

        em.persist(sub);

        // 创建审核工单
        String ticketType = "submission_review";
        String ticketContent = String.format("[用户提交收录] 类型=%s 名称=%s ID=%s 提交者=%s",
            targetType, title, targetId != null ? targetId : "-", submitter.getUsername());
        TicketEntity ticket = ticketService.createTicket(ticketType, submitter, ticketContent);
        sub.setTicketId(ticket.getTicketId());

        log.info("Submission created: id={} type={} title={} submitter={}",
            sub.getId(), targetType, title, submitter.getUserId());

        return new SubmitResult(true, "提交成功，等待管理员审核", sub.getId());
    }

    /**
     * 查询自己的提交记录
     */
    public List<SubmissionEntity> getMySubmissions(Long userId, int page, int size) {
        return em.createQuery(
            "FROM SubmissionEntity s WHERE s.submitterId = :uid ORDER BY s.createdAt DESC",
            SubmissionEntity.class)
            .setParameter("uid", userId)
            .setFirstResult((page - 1) * size)
            .setMaxResults(size)
            .getResultList();
    }

    /**
     * 管理员：列出待审核提交
     */
    public List<SubmissionEntity> listPending(int page, int size) {
        return em.createQuery(
            "FROM SubmissionEntity s WHERE s.status = 'PENDING' ORDER BY s.createdAt ASC",
            SubmissionEntity.class)
            .setFirstResult((page - 1) * size)
            .setMaxResults(size)
            .getResultList();
    }

    /**
     * 管理员：审核通过 → 创建对应收录实体
     */
    @Transactional
    public String approve(Long submissionId, Long reviewerId, String comment) {
        SubmissionEntity sub = em.find(SubmissionEntity.class, submissionId);
        if (sub == null) return "提交记录不存在";
        if (!sub.getStatus().equals("PENDING")) return "该提交已被处理";

        String targetType = sub.getTargetType();

        // 创建收录实体
        switch (targetType) {
            case "group" -> {
                GroupEntity ge = new GroupEntity();
                try {
                    ge.setGroupId(Long.parseLong(sub.getTargetId()));
                } catch (NumberFormatException e) {
                    return "群组ID格式无效";
                }
                ge.setTitle(sub.getTitle());
                ge.setInviteLink(sub.getInviteLink());
                if (sub.getGroupLabel() != null && !sub.getGroupLabel().isEmpty()) {
                    try {
                        ge.setGroupLabel(GroupEntity.GroupLabel.valueOf(sub.getGroupLabel().toUpperCase()));
                    } catch (IllegalArgumentException ignored) {}
                }
                ge.setActive(true);
                ge.setEnvironmentScore(100);
                em.persist(ge);
                log.info("Group created from submission: id={}", ge.getGroupId());
            }
            case "bot" -> {
                BotEntity be = new BotEntity();
                try {
                    be.setBotId(Long.parseLong(sub.getTargetId()));
                } catch (NumberFormatException e) {
                    return "机器人ID格式无效";
                }
                be.setBotName(sub.getTitle());
                be.setBotUsername(sub.getContact() != null ? sub.getContact() : sub.getTitle());
                be.setBotCreditScore(100);
                be.setActive(true);
                em.persist(be);
                log.info("Bot created from submission: id={}", be.getBotId());
            }
            case "proxy" -> {
                ProxyEntity pe = new ProxyEntity();
                pe.setProtocol(sub.getProtocol());
                pe.setEndpoint(sub.getEndpoint());
                pe.setCountryCode("UNKNOWN");
                pe.setActive(true);
                pe.setProxyCreditScore(100);
                em.persist(pe);
                log.info("Proxy created from submission: id={}", pe.getId());
            }
            default -> {
                return "未知收录类型: " + targetType;
            }
        }

        // 更新提交状态
        sub.setStatus("APPROVED");
        sub.setReviewerId(reviewerId);
        sub.setReviewedAt(LocalDateTime.now());
        sub.setReviewComment(comment != null ? comment : "审核通过");

        // 关闭关联工单
        if (sub.getTicketId() != null) {
            ticketService.passTicket(sub.getTicketId(), reviewerId, "收录审核通过");
        }

        return "审核通过，已创建 " + targetType + " 收录";
    }

    /**
     * 管理员：驳回提交
     */
    @Transactional
    public String reject(Long submissionId, Long reviewerId, String reason) {
        SubmissionEntity sub = em.find(SubmissionEntity.class, submissionId);
        if (sub == null) return "提交记录不存在";
        if (!sub.getStatus().equals("PENDING")) return "该提交已被处理";

        sub.setStatus("REJECTED");
        sub.setReviewerId(reviewerId);
        sub.setReviewedAt(LocalDateTime.now());
        sub.setReviewComment(reason != null ? reason : "驳回");

        if (sub.getTicketId() != null) {
            ticketService.punishTicket(sub.getTicketId(), reviewerId, "收录审核驳回: " + reason);
        }

        log.info("Submission rejected: id={} reason={}", submissionId, reason);
        return "已驳回";
    }
}
