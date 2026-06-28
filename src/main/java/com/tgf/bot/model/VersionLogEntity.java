package com.tgf.bot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * VersionLogEntity — 版本日志实体。
 * 记录系统的版本发布历史，包含版本号、发布日期、更新内容等。
 * @since 1.0
 */
@Entity
@Table(name = "version_log")
public class VersionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 16)
    private String version;                 // 版本号 v1.0.0

    private LocalDateTime releaseDate;

    @Column(columnDefinition = "TEXT")
    private String changelog;               // 更新内容

    private boolean isMajor;                // 是否主版本

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public LocalDateTime getReleaseDate() { return releaseDate; }
    public void setReleaseDate(LocalDateTime releaseDate) { this.releaseDate = releaseDate; }
    public String getChangelog() { return changelog; }
    public void setChangelog(String changelog) { this.changelog = changelog; }
    public boolean isMajor() { return isMajor; }
    public void setMajor(boolean major) { isMajor = major; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
