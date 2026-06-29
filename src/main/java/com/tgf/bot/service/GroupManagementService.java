package com.tgf.bot.service;

import com.tgf.bot.model.GroupEntity;
import com.tgf.bot.model.TicketEntity;
import com.tgf.bot.repository.GroupRepository;
import com.tgf.bot.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GroupManagementService — 群组管理服务。
 *
 * 负责标签审计、管理员同步、死罪群组处置等群组管理功能。
 *
 * <p>核心功能：</p>
 * <ul>
 *   <li>标签申请 — 群主申请 NSFW/GAMBLING 标签，生成审核工单</li>
 *   <li>标签合规审计 — 采样检查标签内容匹配率，低于 60% 触发违规</li>
 *   <li>三级阶梯惩罚 — 第 1 次警告，第 2 次最后警告，第 3 次清除收录</li>
 *   <li>安全港死罪处置 — 累计 3 条死罪自动撤销标签，群主扣 30 分</li>
 *   <li>管理员同步 — 同步 Telegram 群组管理员列表</li>
 *   <li>群规设置 — 更新群组描述</li>
 *   <li>退出群组 — 群主主动退出，7 天内不可重新接入</li>
 * </ul>
 *
 * <p>依赖：</p>
 * <ul>
 *   <li>{@link GroupRepository} — 群组数据持久化</li>
 *   <li>{@link UserRepository} — 用户数据查询</li>
 *   <li>{@link CreditEngine} — 标签违规连带扣分</li>
 *   <li>{@link TicketService} — 标签申请工单生成</li>
 * </ul>
 *
 * <p>被引用：</p>
 * <ul>
 *   <li>{@link PenaltyEngine} — 安全港死罪累计调用 handleDeathInSafeHaven()</li>
 *   <li>{@link BotScheduler} — 每周标签合规审计</li>
 *   <li>{@link GroupHandler} — 群组命令（set_label/set_rules/leave_group/sync_admins）</li>
 * </ul>
 *
 * @since 1.0
 */
@Service
public class GroupManagementService {

    private static final Logger log = LoggerFactory.getLogger(GroupManagementService.class);

    @PersistenceContext
    private EntityManager em;

    private final GroupRepository groupRepo;
    private final UserRepository userRepo;
    private final CreditEngine creditEngine;
    private final TicketService ticketService;

    public GroupManagementService(GroupRepository groupRepo, UserRepository userRepo,
                                  CreditEngine creditEngine, TicketService ticketService) {
        this.groupRepo = groupRepo;
        this.userRepo = userRepo;
        this.creditEngine = creditEngine;
        this.ticketService = ticketService;
    }

    // ===== 标签审计 =====

    /** 群主申请标签 - 生成审核工单 */
    @Transactional
    public String applyLabel(Long groupId, Long ownerId, GroupEntity.GroupLabel label) {
        var group = groupRepo.findById(groupId).orElse(null);
        if (group == null) return "❌ 群组未收录";
        if (group.isLabelReapplyForbidden()) return "❌ 该群已被永久禁止申请特殊标签";
        if (group.getGroupLabel() == label) return "❌ 已存在该标签";

        var owner = userRepo.findById(ownerId).orElse(null);
        if (owner == null) return "❌ 用户不存在";
        if (owner.getCreditScore() < 60) return "❌ 信用分需≥60才能申请标签";

        // 创建工单
        TicketEntity ticket = ticketService.createTicket("label_apply", owner, label.name(), groupId);
        em.persist(ticket);

        return "✅ " + label.name() + " 标签申请已提交，24小时内审核。";
    }

    /** 标签合规审计 - 采样检查标签合规 */
    public boolean auditLabelCompliance(List<String> sampleMessages, String categoryRequired, double requiredRatio) {
        if (sampleMessages.isEmpty()) return false;

        long matchCount = sampleMessages.stream()
            .filter(msg -> msg.toLowerCase().contains(categoryRequired.toLowerCase()) ||
                           msg.toLowerCase().contains("nsfw") ||
                           msg.toLowerCase().contains("gambling") ||
                           msg.toLowerCase().contains("赌") ||
                           msg.toLowerCase().contains("色"))
            .count();

        return (double) matchCount / sampleMessages.size() >= requiredRatio;
    }

    /** 标签审计违规 - 三级阶梯惩罚 */
    @Transactional
    public String handleLabelViolation(Long groupId, int violationCount) {
        var group = groupRepo.findById(groupId).orElse(null);
        if (group == null) return "群组不存在";

        // 获取群主
        var admins = em.createQuery(
            "FROM GroupAdminEntity WHERE groupId = :gid AND role = 'creator' AND isActive = true",
            com.tgf.bot.model.GroupAdminEntity.class)
            .setParameter("gid", groupId)
            .getResultList();

        long ownerId = admins.isEmpty() ? 0 : admins.get(0).getUserId();

        switch (violationCount) {
            case 1 -> {
                // 第一次：警告 + 群组信用分-15
                group.setEnvironmentScore(Math.max(0, group.getEnvironmentScore() - 15));
                group.setLabelAuditViolations(1);
                groupRepo.save(group);
                return "⚠️ 标签审计第1次违规：警告，群组环境信用分-15。";
            }
            case 2 -> {
                // 第二次：最后警告 + 信用分-20 + 标签有效期缩短至7天
                group.setEnvironmentScore(Math.max(0, group.getEnvironmentScore() - 20));
                group.setLabelAuditViolations(2);
                group.setLabelExpireAt(LocalDateTime.now().plusDays(7));
                groupRepo.save(group);
                return "⚠️ 标签审计第2次违规：最后警告，环境分-20，标签缩至7天。";
            }
            case 3 -> {
                // 第三次：清除收录 + 群主信用分-20
                group.setActive(false);
                group.setGroupLabel(GroupEntity.GroupLabel.NONE);
                group.setLabelAuditViolations(0);
                group.setInvalidReason("标签审计3次违规");
                group.setInvalidatedAt(LocalDateTime.now());
                groupRepo.save(group);

                if (ownerId != 0) {
                    creditEngine.apply(ownerId, -20, "punish", "群组标签3次违规连带扣分", "system", null);
                }
                return "🔴 标签审计第3次违规：清除收录，群主信用分-20。";
            }
            default -> {
                return "违规次数超出范围";
            }
        }
    }

    /** 安全港群内累计3条死罪 - 自动撤销标签+群主重罚 */
    @Transactional
    public String handleDeathInSafeHaven(Long groupId, int deathCount) {
        var group = groupRepo.findById(groupId).orElse(null);
        if (group == null) return "";
        if (group.getGroupLabel() == GroupEntity.GroupLabel.NONE) return "";

        if (deathCount >= 3) {
            group.setGroupLabel(GroupEntity.GroupLabel.NONE);
            group.setLabelReapplyForbidden(true);
            group.setLabelAuditViolations(0);

            // 查群主，扣30分
            var admins = em.createQuery(
                "FROM GroupAdminEntity WHERE groupId = :gid AND role = 'creator' AND isActive = true",
                com.tgf.bot.model.GroupAdminEntity.class)
                .setParameter("gid", groupId)
                .getResultList();
            if (!admins.isEmpty()) {
                creditEngine.apply(admins.get(0).getUserId(), -30, "punish",
                    "安全港死罪超限群主连带", "system", null);
            }

            groupRepo.save(group);
            return "🔴 安全港累计3条死罪：标签已撤销，群主扣30分，永久禁止申请特殊标签。";
        }
        return "";
    }

    // ===== 管理员同步 =====

    /** 同步群组管理员列表 */
    @Transactional
    public void syncAdmins(Long groupId, List<AdminInfo> adminList) {
        // 标记旧的为不活跃
        em.createQuery(
            "UPDATE GroupAdminEntity SET isActive = false WHERE groupId = :gid")
            .setParameter("gid", groupId)
            .executeUpdate();

        // 插入新列表
        for (var a : adminList) {
            var entity = new com.tgf.bot.model.GroupAdminEntity();
            entity.setGroupId(groupId);
            entity.setUserId(a.userId);
            entity.setRole(a.role);
            entity.setActive(true);
            em.persist(entity);
        }

        log.info("Synced {} admins for group {}", adminList.size(), groupId);
    }

    public record AdminInfo(long userId, String role) {}

    // ==================================================================
    // 群组管理功能
    // ==================================================================

    @Transactional
    public String setRules(Long groupId, Long operatorId, String rules) {
        var group = groupRepo.findById(groupId).orElse(null);
        if (group == null) return "群组未收录";
        group.setDescription(rules);
        groupRepo.save(group);
        return "群规已更新";
    }

    /** 退出群组（群主主动退出） */
    @Transactional
    public String leaveGroup(Long groupId, Long operatorId) {
        var group = groupRepo.findById(groupId).orElse(null);
        if (group == null) return "群组未收录";

        group.setActive(false);
        group.setGroupLabel(GroupEntity.GroupLabel.NONE);
        group.setInvalidReason("群主主动退出");
        group.setInvalidatedAt(LocalDateTime.now());
        groupRepo.save(group);

        return "已退出群组，7天内不可重新接入。";
    }

    @Transactional
    public void addAdmin(long adminId, long targetUserId, String role) {
        var entity = new com.tgf.bot.model.GroupAdminEntity();
        entity.setGroupId(0L); // 全局管理员
        entity.setUserId(targetUserId);
        entity.setRole(role);
        entity.setActive(true);
        em.persist(entity);
    }

    @Transactional
    public void removeAdmin(long targetUserId) {
        em.createQuery(
            "UPDATE GroupAdminEntity SET isActive = false WHERE userId = :uid")
            .setParameter("uid", targetUserId)
            .executeUpdate();
    }
}
