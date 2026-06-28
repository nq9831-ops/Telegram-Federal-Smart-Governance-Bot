package com.tgf.bot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tgf.bot.model.SystemConfigEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * ConfigService — 配置中心服务。
 * 
 * 基于数据库的键值对动态配置，支持运行时调整参数而无需重启，
 * 配置项通过 Spring Cache 缓存。
 * @since 1.0
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    @PersistenceContext
    private EntityManager em;

    private final ObjectMapper mapper = new ObjectMapper();

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

    public void saveTemplate(String name) {
        // 全量配置快照
    }

    public void loadTemplate(String name) {
        // 从 version_log 读取
    }

    @Transactional
    @CacheEvict(value = "config", allEntries = true)
    public void reset() {
        em.createQuery("DELETE FROM SystemConfigEntity").executeUpdate();
    }
}
