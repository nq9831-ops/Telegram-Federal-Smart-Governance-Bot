package com.tgf.bot.repository;

import com.tgf.bot.model.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * UserRepository — 用户数据仓库。
 * 
 * UserEntity 的 JPA 持久化操作接口，包含邀请码查询、用户名查询、
 * 每日广告计数重置等自定义方法。
 * @since 1.0
 */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByInviteCode(String inviteCode);

    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findByUserId(Long userId);

    @Query("SELECT COUNT(u) FROM UserEntity u WHERE u.creditScore >= :min")
    long countByCreditScoreAtLeast(int min);

    @Modifying
    @Query("UPDATE UserEntity u SET u.dailyAdCount = 0, u.dailyResetAt = CURRENT_TIMESTAMP WHERE u.dailyResetAt IS NULL OR u.dailyResetAt < CURRENT_DATE")
    void resetDailyAdCounts();
}
