package com.tgf.bot.controller;

import com.tgf.bot.model.AuditLogEntity;
import com.tgf.bot.model.UserEntity;
import com.tgf.bot.repository.UserRepository;
import com.tgf.bot.service.*;
import com.tgf.bot.util.UrlSecurityValidator;
import com.tgf.bot.config.ApiAuthAspect;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * MiniAppController — Mini App REST API 控制器。
 * 
 * 提供评分、排行榜、收录提交、审核面板等 RESTful 接口，
 * 供前端 Mini App 调用（规格书第 18 章）。
 * @since 1.0
 */
@RestController
@RequestMapping("/api")
public class MiniAppController {

    private static final Logger log = LoggerFactory.getLogger(MiniAppController.class);

    @PersistenceContext
    private EntityManager em;

    private final UserRepository userRepo;
    private final RankingEngine rankingEngine;
    private final TicketService ticketService;
    private final CaptchaService captchaService;
    private final CircuitBreakerService circuitBreaker;
    private final ColdStartService coldStartService;
    private final SubmissionService submissionService;
    private final RatingService ratingService;
    private final ApiAuthAspect apiAuth;
    private final GroupCircuitBreakerService groupBreaker;

    @Value("${spring.application.name:tg-federal-bot}")
    private String appVersion;

    public MiniAppController(UserRepository userRepo,
                             RankingEngine rankingEngine, TicketService ticketService,
                             CaptchaService captchaService, CircuitBreakerService circuitBreaker,
                             ColdStartService coldStartService,
                             SubmissionService submissionService, RatingService ratingService,
                             ApiAuthAspect apiAuth,
                             GroupCircuitBreakerService groupBreaker) {
        this.userRepo = userRepo;
        this.rankingEngine = rankingEngine;
        this.ticketService = ticketService;
        this.captchaService = captchaService;
        this.circuitBreaker = circuitBreaker;
        this.coldStartService = coldStartService;
        this.submissionService = submissionService;
        this.ratingService = ratingService;
        this.apiAuth = apiAuth;
        this.groupBreaker = groupBreaker;
    }

    @GetMapping("/users/{userId}")
    public Map<String, Object> getUserProfile(
        @PathVariable Long userId,
        @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        // 鉴权：只能查看自己的信息，或管理员可查看所有
        boolean isAdmin = apiAuth.checkAdmin();
        boolean isSelf = false;
        try {
            isSelf = headerUserId != null && !headerUserId.isBlank()
                && Long.parseLong(headerUserId) == userId;
        } catch (NumberFormatException ignored) {}

        if (!isAdmin && !isSelf) {
            // 匿名访问：只返回公开信息
            var user = userRepo.findById(userId).orElse(null);
            if (user == null) {
                return Map.of("error", "用户不存在");
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("user_id", user.getUserId());
            resp.put("username", user.getUsername());
            resp.put("credit_score", user.getCreditScore());
            resp.put("rank", CreditEngine.Rank.of(user.getCreditScore()).label);
            resp.put("is_advertiser", user.isCertifiedAdvertiser());
            resp.put("is_frozen", user.isFrozen());
            resp.put("profile_completeness", user.getProfileCompleteness());
            // 公开评分统计
            try {
                var ratingStats = em.createNativeQuery(
                    "SELECT AVG(r.score), COUNT(r) FROM rating_v2 r WHERE r.entity_type = 1 AND r.entity_id = :uid")
                    .setParameter("uid", userId)
                    .getSingleResult();
                if (ratingStats instanceof Object[] arr && arr[0] != null) {
                    resp.put("avg_rating", ((Number) arr[0]).doubleValue());
                    resp.put("rating_count", ((Number) arr[1]).intValue());
                }
            } catch (Exception e) {
                log.debug("Failed to get rating stats for user {}: {}", userId, e.getMessage());
            }
            return resp;
        }

        // 本人或管理员：返回完整信息
        var user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            return Map.of("error", "用户不存在");
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("user_id", user.getUserId());
        resp.put("username", user.getUsername());
        resp.put("credit_score", user.getCreditScore());
        resp.put("rank", CreditEngine.Rank.of(user.getCreditScore()).label);
        resp.put("risk_level", user.getDeepseekRiskLevel().name());
        resp.put("is_advertiser", user.isCertifiedAdvertiser());
        resp.put("is_frozen", user.isFrozen());
        resp.put("is_group_jumper", user.isGroupJumper());
        resp.put("invite_count", user.getInviteCount());
        resp.put("lang", user.getLang());
        resp.put("profile_completeness", user.getProfileCompleteness());

        // 评分统计（从评分服务拉）
        try {
            var ratingStats = em.createNativeQuery(
                "SELECT AVG(r.score), COUNT(r) FROM rating_v2 r WHERE r.entity_type = 1 AND r.entity_id = :uid")
                .setParameter("uid", userId)
                .getSingleResult();
            if (ratingStats instanceof Object[] arr && arr[0] != null) {
                resp.put("avg_rating", ((Number) arr[0]).doubleValue());
                resp.put("rating_count", ((Number) arr[1]).intValue());
            }
        } catch (Exception e) {
            log.debug("Failed to get rating stats for user {}: {}", userId, e.getMessage());
        }

        return resp;
    }

    @GetMapping("/search")
    public Map<String, Object> search(
        @RequestParam(defaultValue = "") String keyword,
        @RequestParam(required = false) Integer type,
        @RequestParam(defaultValue = "") String country,
        @RequestParam(defaultValue = "members") String sortBy,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Map<String, Object> resp = new LinkedHashMap<>();

        // 简化搜索：直接从ES拉或者从group表查询
        // 转义 LIKE 通配符（% 和 _），防止用户注入搜索模式
        String escapedKeyword = keyword.replace("%", "\\%").replace("_", "\\_");
        String escapedCountry = country.replace("%", "\\%").replace("_", "\\_");

        // 先查总数（用于分页）
        long total = em.createQuery(
            "SELECT COUNT(g) FROM GroupEntity g WHERE " +
            "(:keyword = '' OR g.title LIKE :kw ESCAPE '\\' OR g.description LIKE :dkw ESCAPE '\\') " +
            "AND (:country = '' OR g.username LIKE :c ESCAPE '\\') " +
            "AND g.isActive = true",
            Long.class)
            .setParameter("keyword", keyword)
            .setParameter("kw", "%" + escapedKeyword + "%")
            .setParameter("dkw", "%" + escapedKeyword + "%")
            .setParameter("country", country)
            .setParameter("c", country.isEmpty() ? "%" : "%" + escapedCountry + "%")
            .getSingleResult();

        var groups = em.createQuery(
            "FROM GroupEntity g WHERE " +
            "(:keyword = '' OR g.title LIKE :kw ESCAPE '\\' OR g.description LIKE :dkw ESCAPE '\\') " +
            "AND (:country = '' OR g.username LIKE :c ESCAPE '\\') " +
            "AND g.isActive = true " +
            "ORDER BY g.memberCount DESC",
            com.tgf.bot.model.GroupEntity.class)
            .setParameter("keyword", keyword)
            .setParameter("kw", "%" + escapedKeyword + "%")
            .setParameter("dkw", "%" + escapedKeyword + "%")
            .setParameter("country", country)
            .setParameter("c", country.isEmpty() ? "%" : "%" + escapedCountry + "%")
            .setMaxResults(size)
            .setFirstResult((page - 1) * size)
            .getResultList();

        List<Map<String, Object>> records = new ArrayList<>();
        for (var g : groups) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", g.getGroupId().toString());
            m.put("type", 1);
            m.put("title", g.getTitle());
            m.put("description", g.getDescription());
            m.put("username", g.getUsername());
            m.put("country", "");
            m.put("language", "");
            m.put("members", g.getMemberCount());
            m.put("avg_rating", 0);
            m.put("rating_count", 0);
            records.add(m);
        }

        resp.put("records", records);
        resp.put("total", total);
        resp.put("page", page);
        resp.put("size", size);
        return resp;
    }

    @GetMapping("/detail/{uuid}")
    public Map<String, Object> detail(
        @PathVariable String uuid,
        @RequestParam(required = false) Long userId
    ) {
        // 简化：用uuid查群组
        try {
            Long groupId = Long.parseLong(uuid);
            var g = em.find(com.tgf.bot.model.GroupEntity.class, groupId);
            if (g != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("uuid", uuid);
                m.put("type", 1);
                m.put("title", g.getTitle());
                m.put("description", g.getDescription());
                m.put("username", g.getUsername());
                m.put("members", g.getMemberCount());
                m.put("country", "");
                m.put("language", "");
                m.put("classification", g.getGroupLabel().name());
                m.put("flagged", false);
                m.put("avg_rating", 0);
                m.put("rating_count", 0);
                m.put("r1", 0); m.put("r2", 0); m.put("r3", 0); m.put("r4", 0); m.put("r5", 0);

                if (userId != null) {
                    // 查询用户自己的评分
                    var userRating = ratingService.getUserRating(1, groupId, userId);
                    if (userRating != null) {
                        m.put("my_rating", userRating.getScore());
                    }
                }
                return m;
            }
        } catch (Exception e) {
            log.debug("Failed to get group detail: {}", e.getMessage());
        }

        return Map.of("error", "not found");
    }

    @PostMapping("/rating/{uuid}")
    public Map<String, Object> rate(
        @PathVariable String uuid,
        @RequestParam int score,
        @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        if (score < 1 || score > 5) {
            return Map.of("success", false, "message", "评分必须在1~5星之间");
        }

        // userId 从 header 获取，防止伪造
        if (headerUserId == null || headerUserId.isBlank()) {
            return Map.of("success", false, "message", "缺少 X-User-Id 头");
        }
        long userId;
        try {
            userId = Long.parseLong(headerUserId);
        } catch (NumberFormatException e) {
            return Map.of("success", false, "message", "X-User-Id 格式错误");
        }

        // 管理员可以代评分，普通用户只能给自己评分（userId 已从 header 获取，天然一致）
        boolean isAdmin = apiAuth.checkAdmin();

        // entityType 由路径确定：uuid 传的是 "group"/"bot"/"proxy"/"user" + ":" + entityId 组合
        int entityType = 1; // 默认=群组(1)
        Long entityId;
        try {
            if (uuid != null && uuid.contains(":")) {
                String[] parts = uuid.split(":", 2);
                entityType = switch (parts[0].toLowerCase()) {
                    case "user" -> 4;
                    case "group" -> 1;
                    case "bot" -> 3;
                    case "proxy" -> 5;
                    default -> 1;
                };
                entityId = Long.parseLong(parts[1]);
            } else {
                entityId = Long.parseLong(uuid);
            }
        } catch (NumberFormatException e) {
            return Map.of("success", false, "message", "无效的实体 ID");
        }

        var result = ratingService.rate(entityType, entityId, userId, score, "", "", "");
        boolean ok = result.success();
        if (ok) {
            // 触发排行榜更新（异步，不阻塞返回）
            updateRankingForRating(entityType, entityId);
            return Map.of("success", true, "message", "评分成功");
        } else {
            return Map.of("success", false, "message", "评分失败：可能频率限制或验证码错误");
        }
    }

    private void updateRankingForRating(int entityType, Long entityId) {
        // 异步触发对应实体的排行分更新
        try {
            switch (entityType) {
                case 4 -> { // USER
                    var u = userRepo.findById(entityId);
                    u.ifPresent(user -> rankingEngine.updateUserScore(user.getUserId(), user.getCreditScore()));
                }
                case 1 -> { // GROUP
                    var g = em.find(com.tgf.bot.model.GroupEntity.class, entityId);
                    if (g != null) rankingEngine.updateGroupScore(g.getGroupId(), g.getEnvironmentScore());
                }
                case 3 -> { // BOT
                    var b = em.find(com.tgf.bot.model.BotEntity.class, entityId);
                    if (b != null) rankingEngine.updateBotScore(b.getBotId(), 0);
                }
                case 5 -> { // PROXY
                    var p = em.find(com.tgf.bot.model.ProxyEntity.class, entityId);
                    if (p != null) rankingEngine.updateProxyScore(p.getId(), p.getProxyCreditScore());
                }
                default -> {}
            }
        } catch (Exception e) {
            log.debug("Ranking update after rating failed: {}", e.getMessage());
        }
    }

    @GetMapping("/ranking/{type}")
    public Map<String, Object> ranking(
        @PathVariable String type,
        @RequestParam(defaultValue = "20") int top
    ) {
        String redisKey = switch (type) {
            case "user" -> "rank:user";
            case "group" -> "rank:group";
            case "bot" -> "rank:bot";
            case "proxy" -> "rank:proxy";
            default -> "rank:user";
        };

        var rankings = rankingEngine.getRanking(redisKey, top);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var entry : rankings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.getKey());
            item.put("score", entry.getValue());
            items.add(item);
        }

        return Map.of("type", type, "items", items);
    }

    @GetMapping("/review/list")
    public Map<String, Object> reviewList(
        @RequestParam(defaultValue = "") String status,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        if (!apiAuth.checkAdmin()) return Map.of("success", false, "message", "无权限");
        return doReviewList(status, page, size);
    }

    private Map<String, Object> doReviewList(String status, int page, int size) {
        var tickets = ticketService.listPendingTickets(
            status.isEmpty() ? null : status, page, size);

        List<Map<String, Object>> list = new ArrayList<>();
        for (var t : tickets) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticket_id", t.getTicketId());
            m.put("ticket_type", t.getTicketType());
            m.put("status", t.getStatus());
            m.put("priority", t.getPriority());
            m.put("target_user_id", t.getTargetUserId());
            m.put("content", t.getContent());

            if (t.getDeadlineAt() != null) {
                long min = java.time.Duration.between(
                    java.time.LocalDateTime.now(), t.getDeadlineAt()).toMinutes();
                m.put("sla_minutes", min);
            }
            m.put("cold_start", t.isColdStart());
            m.put("created_at", t.getCreatedAt() != null ? t.getCreatedAt().toString() : "");
            list.add(m);
        }

        return Map.of("tickets", list, "total", (long) list.size());
    }


    @PostMapping("/review/pass/{id}")
    public Map<String, Object> reviewPass(@PathVariable Long id) {
        if (!apiAuth.checkAdmin()) return Map.of("success", false, "message", "无权限");
        ticketService.passTicket(id, 0L, "Mini App 操作");
        return Map.of("success", true);
    }

    @PostMapping("/review/punish/{id}")
    public Map<String, Object> reviewPunish(@PathVariable Long id) {
        if (!apiAuth.checkAdmin()) return Map.of("success", false, "message", "无权限");
        ticketService.punishTicket(id, 0L, "Mini App 操作");
        return Map.of("success", true);
    }

    @PostMapping("/review/escalate/{id}")
    public Map<String, Object> reviewEscalate(@PathVariable Long id) {
        if (!apiAuth.checkAdmin()) return Map.of("success", false, "message", "无权限");
        ticketService.escalateTicket(id);
        return Map.of("success", true);
    }

    @GetMapping("/captcha")
    public Map<String, Object> captcha() {
        var result = captchaService.generate();
        return Map.of("captcha_id", result.captchaId(), "image", result.imageBase64());
    }

    @GetMapping("/system/status")
    public Map<String, Object> systemStatus() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("version", "v1.0.0");
        resp.put("cold_start", coldStartService.isColdStartActive());
        resp.put("cold_start_day", coldStartService.getColdStartDay());
        resp.put("circuit_breaker", circuitBreaker.getCurrentState().name());
        resp.put("records_count", 0);
        resp.put("users_count", userRepo.count());
        resp.put("circuit_broken_groups", countBrokenGroups());
        return resp;
    }

    @GetMapping("/credit/{userId}")
    public Map<String, Object> credit(
        @PathVariable Long userId,
        @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        // 鉴权：只能查看自己的信用记录，或管理员可查看所有
        boolean isAdmin = apiAuth.checkAdmin();
        boolean isSelf = false;
        try {
            isSelf = headerUserId != null && !headerUserId.isBlank()
                && Long.parseLong(headerUserId) == userId;
        } catch (NumberFormatException ignored) {}

        if (!isAdmin && !isSelf) {
            return Map.of("error", "无权限查看他人信用记录");
        }

        var user = userRepo.findById(userId).orElse(null);
        if (user == null) return Map.of("error", "用户不存在");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("credit_score", user.getCreditScore());
        resp.put("rank", CreditEngine.Rank.of(user.getCreditScore()).label);
        resp.put("can_enroll", user.getCreditScore() >= 30);
        resp.put("can_report", user.getCreditScore() >= 20);

        // 最近10条信用变更记录
        List<AuditLogEntity> logs = em.createQuery(
            "FROM AuditLogEntity WHERE targetUserId = :uid ORDER BY createdAt DESC", AuditLogEntity.class)
            .setParameter("uid", userId)
            .setMaxResults(10)
            .getResultList();

        List<Map<String, Object>> history = new ArrayList<>();
        for (var l : logs) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("action", l.getActionType());
            h.put("change", l.getAfterValue() - l.getBeforeValue());
            h.put("before", l.getBeforeValue());
            h.put("after", l.getAfterValue());
            h.put("reason", l.getReason());
            h.put("time", l.getCreatedAt() != null ? l.getCreatedAt().toString() : "");
            history.add(h);
        }
        resp.put("history", history);

        return resp;
    }

    @GetMapping("/group/circuit/{chatId}")
    public Map<String, Object> groupCircuit(@PathVariable Long chatId) {
        if (!apiAuth.checkAdmin()) return Map.of("success", false, "message", "无权限");
        return groupBreaker.getGroupStatus(chatId);
    }

    @PostMapping("/group/recover/{chatId}")
    public Map<String, Object> groupRecover(@PathVariable Long chatId) {
        if (!apiAuth.checkAdmin()) return Map.of("success", false, "message", "无权限");
        groupBreaker.manualRecover(chatId);
        return Map.of("success", true, "message", "群组熔断已恢复");
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "ok",
            "users", userRepo.count(),
            "state", circuitBreaker.getCurrentState().label,
            "broken_groups", countBrokenGroups()
        );
    }

    // ============================
    //  用户提交收录
    // ============================

    @PostMapping("/submission/submit")
    @Transactional
    public Map<String, Object> submitSubmission(
        @RequestParam String targetType,
        @RequestParam String title,
        @RequestParam(required = false) String description,
        @RequestParam(required = false) String contact,
        @RequestParam(required = false) String inviteLink,
        @RequestParam(required = false) String groupLabel,
        @RequestParam(required = false) String protocol,
        @RequestParam(required = false) String endpoint,
        @RequestParam(required = false) String targetId,
        @RequestParam long userId,
        @RequestParam(required = false) String captchaId,
        @RequestParam(required = false) String captchaInput,
        @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        // 身份校验：userId 必须与头信息一致，或管理员 Token 跳过
        boolean isAdmin = apiAuth.checkAdmin();
        boolean isSelf = false;
        try {
            isSelf = headerUserId != null && !headerUserId.isBlank()
                && Long.parseLong(headerUserId) == userId;
        } catch (NumberFormatException ignored) {}

        if (!isAdmin && !isSelf) {
            return Map.of("success", false, "message", "身份验证失败：请使用 X-User-Id 头验证身份");
        }

        UserEntity user = userRepo.findById(userId).orElse(null);
        if (user == null) return Map.of("success", false, "message", "用户不存在");

        // URL 安全校验（防 SSRF/钓鱼/内网地址）
        String urlError = UrlSecurityValidator.validate(inviteLink);
        if (urlError != null) return Map.of("success", false, "message", urlError);
        if (endpoint != null && !endpoint.isBlank()) {
            String epError = UrlSecurityValidator.validate(endpoint);
            if (epError != null) return Map.of("success", false, "message", "endpoint: " + epError);
        }

        var validate = submissionService.validate(targetType, title, captchaId, captchaInput);
        if (!validate.success()) return Map.of("success", false, "message", validate.message());

        var result = submissionService.submit(
            targetType, title, description, contact,
            inviteLink, groupLabel, protocol, endpoint, targetId,
            user, captchaId, captchaInput, "");

        return Map.of("success", result.success(), "message", result.message());
    }

    @GetMapping("/submission/my")
    public Map<String, Object> mySubmissions(
        @RequestParam long userId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        // 身份校验：userId 必须与头信息一致，或管理员 Token 跳过
        if (headerUserId != null) {
            try {
                if (Long.parseLong(headerUserId) != userId && !apiAuth.checkAdmin()) {
                    return Map.of("items", List.of(), "total", 0L);
                }
            } catch (NumberFormatException e) {
                return Map.of("items", List.of(), "total", 0L);
            }
        }
        var list = submissionService.getMySubmissions(userId, page, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var s : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("target_type", s.getTargetType());
            m.put("title", s.getTitle());
            m.put("status", s.getStatus());
            m.put("review_comment", s.getReviewComment());
            m.put("created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : "");
            items.add(m);
        }
        return Map.of("items", items, "total", (long) items.size());
    }

    @GetMapping("/submission/pending")
    public Map<String, Object> pendingSubmissions(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        if (!apiAuth.checkAdmin()) return Map.of("items", List.of(), "total", 0L);
        var list = submissionService.listPending(page, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var s : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("target_type", s.getTargetType());
            m.put("title", s.getTitle());
            m.put("description", s.getDescription());
            m.put("contact", s.getContact());
            m.put("invite_link", s.getInviteLink());
            m.put("group_label", s.getGroupLabel());
            m.put("protocol", s.getProtocol());
            m.put("endpoint", s.getEndpoint());
            m.put("submitter_id", s.getSubmitterId());
            m.put("submitter_username", s.getSubmitterUsername());
            m.put("ticket_id", s.getTicketId());
            m.put("created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : "");
            items.add(m);
        }
        return Map.of("items", items, "total", (long) items.size());
    }

    @PostMapping("/submission/approve")
    @Transactional
    public Map<String, Object> approveSubmission(
        @RequestParam Long submissionId,
        @RequestParam Long reviewerId,
        @RequestParam(required = false) String comment
    ) {
        if (!apiAuth.checkAdmin()) return Map.of("success", false, "message", "无权限");
        String msg = submissionService.approve(submissionId, reviewerId, comment);
        return Map.of("success", msg.startsWith("审核通过"), "message", msg);
    }

    @PostMapping("/submission/reject")
    @Transactional
    public Map<String, Object> rejectSubmission(
        @RequestParam Long submissionId,
        @RequestParam Long reviewerId,
        @RequestParam(required = false) String reason
    ) {
        if (!apiAuth.checkAdmin()) return Map.of("success", false, "message", "无权限");
        String msg = submissionService.reject(submissionId, reviewerId, reason);
        return Map.of("success", msg.equals("已驳回"), "message", msg);
    }

    /** 查询当前熔断群组数量 */
    private long countBrokenGroups() {
        try {
            return em.createQuery(
                "SELECT COUNT(g) FROM GroupEntity g WHERE g.circuitBroken = true", Long.class)
                .getSingleResult();
        } catch (Exception e) {
            return 0;
        }
    }

}
