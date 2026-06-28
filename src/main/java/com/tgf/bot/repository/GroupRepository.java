package com.tgf.bot.repository;

import com.tgf.bot.model.GroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 群组数据仓库 - GroupEntity 持久化操作。 */
@Repository
/**
 * GroupRepository — 群组数据仓库。
 * 
 * GroupEntity 的 JPA 持久化操作接口。
 * @since 1.0
 */
public interface GroupRepository extends JpaRepository<GroupEntity, Long> {

    List<GroupEntity> findByIsActiveTrue();

    List<GroupEntity> findByGroupLabelAndIsActiveTrue(GroupEntity.GroupLabel label);

    List<GroupEntity> findByGroupLabelNotAndIsActiveTrue(GroupEntity.GroupLabel label);
}
