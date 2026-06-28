package com.tgf.bot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * SystemConfigEntity — 系统配置实体。
 * 以键值对形式存储系统级配置项，支持动态调整参数而无需重启服务。
 * @since 1.0
 */
@Entity
@Table(name = "system_config")
public class SystemConfigEntity {

    @Id
    @Column(length = 64)
    private String configKey;               // 配置键名

    @Column(columnDefinition = "TEXT")
    private String configValue;             // JSON格式值

    @Column(length = 16)
    private String configType;              // boolean / integer / float / string

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }
    public String getConfigType() { return configType; }
    public void setConfigType(String configType) { this.configType = configType; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
