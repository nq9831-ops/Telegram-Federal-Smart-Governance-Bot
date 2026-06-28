package com.tgf.bot.service;

import com.tgf.bot.model.TicketEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * TicketService — 工单服务。
 * 
 * 管理死罪复核、标签申请、认证广告商、普通申诉等审核流程，
 * 含 SLA 超时自动升级机制。
 * @since 1.0
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    @PersistenceContext
    private EntityManager em;

    public TicketService() {}

    public TicketEntity createTicket(String ticketType, com.tgf.bot.model.UserEntity user,
                                      String content, Long groupId) {
        TicketEntity ticket = new TicketEntity();
        ticket.setTicketType(ticketType);
        ticket.setStatus("PENDING");
        ticket.setSubmitterId(user != null ? user.getUserId() : 0L);
        ticket.setContent(content);
        ticket.setRelatedGroupId(groupId);

        // 根据类型设置优先级和SLA（规格书15.2）
        switch (ticketType) {
            case "death_review" -> {
                ticket.setPriority(2);
                ticket.setDeadlineAt(LocalDateTime.now().plusMinutes(30));
            }
            case "label_apply" -> {
                ticket.setPriority(0);
                ticket.setDeadlineAt(LocalDateTime.now().plusHours(24));
            }
            case "ad_apply" -> {
                ticket.setPriority(0);
                ticket.setDeadlineAt(LocalDateTime.now().plusHours(48));
            }
            case "appeal" -> {
                ticket.setPriority(0);
                ticket.setDeadlineAt(LocalDateTime.now().plusHours(72));
            }
            case "contact_admin" -> {
                ticket.setPriority(0);
                ticket.setDeadlineAt(LocalDateTime.now().plusDays(7));
            }
            default -> ticket.setDeadlineAt(LocalDateTime.now().plusHours(24));
        }

        em.persist(ticket);
        log.info("Ticket created: type={} id={} submitter={}", ticketType, ticket.getTicketId(), ticket.getSubmitterId());
        return ticket;
    }

    /** 简化调用 */
    public TicketEntity createTicket(String ticketType, com.tgf.bot.model.UserEntity user, String content) {
        return createTicket(ticketType, user, content, null);
    }

    public List<TicketEntity> listPendingTickets(String ticketType, int page, int size) {
        var cb = em.getCriteriaBuilder();
        var cq = cb.createQuery(TicketEntity.class);
        var root = cq.from(TicketEntity.class);

        var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
        predicates.add(cb.equal(root.get("status"), "PENDING"));
        if (ticketType != null && !ticketType.isEmpty()) {
            predicates.add(cb.equal(root.get("ticketType"), ticketType));
        }

        cq.where(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        cq.orderBy(cb.desc(root.get("priority")), cb.asc(root.get("deadlineAt")));

        return em.createQuery(cq)
            .setFirstResult((page - 1) * size)
            .setMaxResults(size)
            .getResultList();
    }

    public TicketEntity getTicket(Long ticketId) {
        return em.find(TicketEntity.class, ticketId);
    }

    @Transactional
    public void passTicket(Long ticketId, Long reviewerId, String comment) {
        var ticket = em.find(TicketEntity.class, ticketId);
        if (ticket == null) return;

        ticket.setStatus("PASSED");
        ticket.setReviewerId(reviewerId);
        ticket.setReviewedAt(LocalDateTime.now());
        ticket.setReviewComment(comment);
        log.info("Ticket passed: id={} reviewer={}", ticketId, reviewerId);
    }

    @Transactional
    public void punishTicket(Long ticketId, Long reviewerId, String comment) {
        var ticket = em.find(TicketEntity.class, ticketId);
        if (ticket == null) return;

        ticket.setStatus("REJECTED");
        ticket.setReviewerId(reviewerId);
        ticket.setReviewedAt(LocalDateTime.now());
        ticket.setReviewComment(comment);
        log.info("Ticket rejected: id={} reviewer={}", ticketId, reviewerId);
    }

    @Transactional
    public void escalateTicket(Long ticketId) {
        var ticket = em.find(TicketEntity.class, ticketId);
        if (ticket == null) return;

        ticket.setEscalationLevel(ticket.getEscalationLevel() + 1);
        ticket.setEscalatedAt(LocalDateTime.now());
        ticket.setStatus("ESCALATED");

        log.warn("Ticket escalated: id={} level={}", ticketId, ticket.getEscalationLevel());
    }

    /** SLA超时检查 - 防拖延升级 */
    @Transactional
    public void checkSlaTimeouts() {
        var now = LocalDateTime.now();

        // 查询超时的PENDING工单
        var tickets = em.createQuery(
            "FROM TicketEntity t WHERE t.status = 'PENDING' AND t.deadlineAt < :now",
            TicketEntity.class)
            .setParameter("now", now)
            .getResultList();

        for (var t : tickets) {
            var deadline = t.getDeadlineAt();
            long overdueMinutes = java.time.Duration.between(deadline, now).toMinutes();
            long slaMinutes = java.time.Duration.between(
                t.getCreatedAt(), deadline).toMinutes();

            // 超时1倍 → 私聊催办
            if (overdueMinutes >= slaMinutes * 1 && t.getEscalationLevel() < 1) {
                escalateTicket(t.getTicketId());
                log.warn("SLA 1x: ticket={} type={} overdue={}min", t.getTicketId(), t.getTicketType(), overdueMinutes);
            }
            // 超时2倍 → 升级至高级审核官
            if (overdueMinutes >= slaMinutes * 2 && t.getEscalationLevel() < 2) {
                escalateTicket(t.getTicketId());
                log.warn("SLA 2x: ticket={} ESCALATED to senior", t.getTicketId());
            }
            // 超时3倍 → 推送至超级管理员
            if (overdueMinutes >= slaMinutes * 3) {
                escalateTicket(t.getTicketId());
                log.error("SLA 3x: ticket={} ESCALATED to super admin", t.getTicketId());
            }
        }
    }

    @Transactional
    public void batchProcess(List<Long> ticketIds, String action, Long reviewerId) {
        int count = 0;
        for (Long id : ticketIds) {
            if (count >= 50) break;
            if ("pass".equals(action)) passTicket(id, reviewerId, "批量处理通过");
            else if ("punish".equals(action)) punishTicket(id, reviewerId, "批量处理处罚");
            count++;
        }
        log.info("Batch processed {} tickets: action={}", count, action);
    }
}
