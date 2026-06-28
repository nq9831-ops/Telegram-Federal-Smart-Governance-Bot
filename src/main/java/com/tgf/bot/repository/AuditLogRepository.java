package com.tgf.bot.repository;

import com.tgf.bot.model.AuditLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AuditLogRepository — 审计日志数据访问层。
 * 提供基于时间范围、操作类型、目标用户/群组的分页查询。
 * @since 1.0
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    /** 按目标用户查询，近到远排序 */
    List<AuditLogEntity> findByTargetUserIdOrderByCreatedAtDesc(Long targetUserId, Pageable pageable);

    /** 按操作类型查询，近到远排序 */
    List<AuditLogEntity> findByActionTypeOrderByCreatedAtDesc(String actionType, Pageable pageable);

    /** 按时间范围查询，近到远排序 */
    List<AuditLogEntity> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime start, LocalDateTime end, Pageable pageable);

    /** 按操作者查询，近到远排序 */
    List<AuditLogEntity> findByOperatorUserIdOrderByCreatedAtDesc(Long operatorUserId, Pageable pageable);
}
