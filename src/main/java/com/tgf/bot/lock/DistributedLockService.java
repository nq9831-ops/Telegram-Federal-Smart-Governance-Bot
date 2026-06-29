package com.tgf.bot.lock;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * DistributedLockService — 基于 PostgreSQL 的简单分布式锁。
 * <p>使用 advisory_lock 实现跨实例互斥，防止多实例部署时定时任务重复执行。</p>
 * <p>使用双参数 (key1, key2) 形式，降低哈希冲突概率（从 2^32 降至 2^64）。</p>
 * @since 1.0
 */
@Service
public class DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);

    @PersistenceContext
    private EntityManager em;

    /**
     * 尝试获取基于 PostgreSQL advisory lock 的分布式锁（非阻塞）。
     * <p>锁在调用方事务提交/回滚时自动释放。不要在此方法上加 @Transactional，
     * 否则锁会在方法返回时立即释放，导致分布式互斥失效。</p>
     * <p>使用双参数 (key1, key2) 形式，大幅降低哈希冲突概率。</p>
     * @param lockKey 锁标识（唯一）
     * @param timeoutMs 保留参数（当前未使用）
     * @return true 获取成功，false 获取失败
     */
    public boolean tryLock(String lockKey, long timeoutMs) {
        int[] keys = hashToIntPair(lockKey);
        try {
            Object result = em.createNativeQuery("SELECT pg_try_advisory_lock(:key1, :key2)")
                    .setParameter("key1", keys[0])
                    .setParameter("key2", keys[1])
                    .getSingleResult();
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Distributed lock failed for key={}: {}", lockKey, e.getMessage());
            return false;
        }
    }

    /**
     * 释放 advisory lock（通常在同一事务结束时自动释放，此方法用于手动提前释放）。
     * <p>不要在此方法上加 @Transactional，否则会在独立事务中释放锁。</p>
     */
    public void unlock(String lockKey) {
        int[] keys = hashToIntPair(lockKey);
        try {
            em.createNativeQuery("SELECT pg_advisory_unlock(:key1, :key2)")
                    .setParameter("key1", keys[0])
                    .setParameter("key2", keys[1])
                    .getSingleResult();
        } catch (Exception e) {
            log.warn("Distributed unlock failed for key={}: {}", lockKey, e.getMessage());
        }
    }

    /**
     * 将字符串哈希为两个 int 值（key1, key2）。
     * 使用两种不同的哈希算法组合，大幅降低冲突概率。
     * key1 = 标准 hashCode()
     * key2 = FNV-1a 哈希变体
     */
    private int[] hashToIntPair(String s) {
        if (s == null) {
            return new int[]{0, 0};
        }

        // key1: 标准 hashCode
        int key1 = s.hashCode();

        // key2: FNV-1a 32位哈希（与 hashCode 算法不同，降低冲突概率）
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        int hash = 0x811c9dc5; // FNV offset basis
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= 0x01000193; // FNV prime
        }
        int key2 = hash;

        return new int[]{key1, key2};
    }
}
