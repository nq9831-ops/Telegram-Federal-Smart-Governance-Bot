package com.tgf.bot.service;

import com.tgf.bot.model.SystemConfigEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * ConfigService — 配置中心服务。
 *
 * 基于数据库（{@link SystemConfigEntity}）的键值对动态配置，
 * 支持运行时调整参数而无需重启，配置项通过 Spring Cache 缓存。
 *
 * <p>功能：</p>
 * <ul>
 *   <li>get(key) — 获取配置值（带缓存）</li>
 *   <li>get(key, defaultValue) — 获取配置值，不存在时返回默认值</li>
 *   <li>getBoolean/getInt — 类型安全的配置读取</li>
 *   <li>set(key, value, type) — 设置配置值（自动清除缓存）</li>
 *   <li>reset() — 恢复出厂设置（清除所有配置）</li>
 * </ul>
 *
 * <p>依赖：</p>
 * <ul>
 *   <li>{@link EntityManager} — JPA 数据持久化</li>
 *   <li>{@link SystemConfigEntity} — 配置实体</li>
 * </ul>
 *
 * <p>被引用：</p>
 * <ul>
 *   <li>{@link AdminHandler} — 管理员配置管理命令</li>
 * </ul>
 *
 * @since 1.0
 */
@Service
public class ConfigService {


    @PersistenceContext
    private EntityManager em;


    @Cacheable(value = "config", key = "#key")
    public String get(String key) {
        var entity = em.find(SystemConfigEntity.class, key);
        return entity != null ? entity.getConfigValue() : null;
    }

    public String get(String key, String defaultValue) {
        String val = get(key);
        return val != null ? val : defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String val = get(key);
        return val != null ? Boolean.parseBoolean(val) : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        String val = get(key);
        if (val == null) return defaultValue;
        try { return Integer.parseInt(val); } catch (Exception e) { return defaultValue; }
    }

    @Transactional
    @CacheEvict(value = "config", key = "#key")
    public void set(String key, String value, String type) {
        var entity = em.find(SystemConfigEntity.class, key);
        if (entity == null) {
            entity = new SystemConfigEntity();
            entity.setConfigKey(key);
        }
        entity.setConfigValue(value);
        entity.setConfigType(type);
        em.persist(entity);
    }

    public void setBoolean(String key, boolean value) {
        set(key, String.valueOf(value), "boolean");
    }

    public void setInt(String key, int value) {
        set(key, String.valueOf(value), "integer");
    }

    @Transactional
    @CacheEvict(value = "config", allEntries = true)
    public void reset() {
        em.createQuery("DELETE FROM SystemConfigEntity").executeUpdate();
    }
}
