package com.tgf.bot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * ProxyEntity — 代理实体。
 * 记录联盟收录的代理服务信息，支持多种协议，评估代理服务质量。
 * @since 1.0
 */
@Entity
@Table(name = "proxies", indexes = {
    @Index(name = "idx_proxy_active", columnList = "isActive"),
    @Index(name = "idx_proxy_credit", columnList = "proxyCreditScore")
})
public class ProxyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 16)
    private String protocol;                // V2Ray / Trojan / Shadowsocks / MTProto

    @Column(length = 256)
    private String endpoint;                // 连接地址

    private boolean isFree;                 // 是否免费

    @Column(length = 8)
    private String countryCode;             // 地理位置国家

    private double onlineRate;              // 在线率 0-100%

    @Column(length = 128)
    private String exitIpRiskLabel;         // 出口IP风险标签

    private int proxyCreditScore = 100;     // 代理信用分 0-100

    private boolean isActive = true;

    private LocalDateTime invalidatedAt;
    @Column(length = 256)
    private String invalidReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ===== getters/setters =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public boolean isFree() { return isFree; }
    public void setFree(boolean free) { isFree = free; }
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public double getOnlineRate() { return onlineRate; }
    public void setOnlineRate(double onlineRate) { this.onlineRate = onlineRate; }
    public String getExitIpRiskLabel() { return exitIpRiskLabel; }
    public void setExitIpRiskLabel(String exitIpRiskLabel) { this.exitIpRiskLabel = exitIpRiskLabel; }
    public int getProxyCreditScore() { return proxyCreditScore; }
    public void setProxyCreditScore(int proxyCreditScore) { this.proxyCreditScore = proxyCreditScore; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public LocalDateTime getInvalidatedAt() { return invalidatedAt; }
    public void setInvalidatedAt(LocalDateTime invalidatedAt) { this.invalidatedAt = invalidatedAt; }
    public String getInvalidReason() { return invalidReason; }
    public void setInvalidReason(String invalidReason) { this.invalidReason = invalidReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
