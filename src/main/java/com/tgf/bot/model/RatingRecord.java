package com.tgf.bot.model;

/**
 * RatingRecord — ES 评分记录。
 * 用于联盟内的用户/群组/机器人/代理四种实体的评分功能（ES 存储，非 JPA）。
 * @since 1.0
 */
public class RatingRecord {

    private String id;
    private int entityType;                 // 1=User 2=Group 3=Bot 4=Proxy
    private Long entityId;                  // 实体的 Telegram ID
    private Long userId;                    // 评分人
    private int score;                      // 1~5
    private String ip;                      // 防作弊用
    private String captchaId;               // 验证码ID
    private long createTime;
    private long updateTime;

    public RatingRecord() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getEntityType() { return entityType; }
    public void setEntityType(int entityType) { this.entityType = entityType; }
    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getCaptchaId() { return captchaId; }
    public void setCaptchaId(String captchaId) { this.captchaId = captchaId; }
    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
    public long getUpdateTime() { return updateTime; }
    public void setUpdateTime(long updateTime) { this.updateTime = updateTime; }
}
