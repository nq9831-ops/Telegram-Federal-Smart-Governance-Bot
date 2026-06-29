package com.tgf.bot.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.*;

/**
 * UrlSecurityValidator — URL 安全校验工具。
 * <p>防止提交恶意链接（SSRF、钓鱼、内网地址）：</p>
 * <ul>
 *   <li>禁止内网/本地地址（127.0.0.1、10.x、172.16-31.x、192.168.x、localhost）</li>
 *   <li>禁止非 http/https 协议（javascript:、data:、file:等）</li>
 *   <li>对域名进行 DNS 解析校验，防止 DNS 重绑定指向内网</li>
 *   <li>禁止特殊保留地址（0.0.0.0、169.254.x.x 等）</li>
 * </ul>
 * @since 1.0
 */
public class UrlSecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(UrlSecurityValidator.class);
    private static final Set<String> ALLOWED_PROTOCOLS = Set.of("http", "https", "tg");
    private static final ExecutorService DNS_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "url-dns-checker");
        t.setDaemon(true);
        return t;
    });
    private static final long DNS_TIMEOUT_MS = 3000;

    static {
        // JVM 关闭时优雅关闭线程池（daemon 线程在 JVM 退出时自动终止，但热重载场景需要显式关闭）
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            DNS_EXECUTOR.shutdown();
            try {
                if (!DNS_EXECUTOR.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    DNS_EXECUTOR.shutdownNow();
                }
            } catch (InterruptedException e) {
                DNS_EXECUTOR.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }, "url-dns-checker-shutdown"));
    }

    /**
     * 校验URL安全性。
     * @param urlStr 待校验URL
     * @return 错误信息，null表示通过
     */
    public static String validate(String urlStr) {
        if (urlStr == null || urlStr.isBlank()) return null; // 允许为空

        String trimmed = urlStr.trim();

        // 允许 tg: 协议链接（Telegram 内部链接）
        if (trimmed.startsWith("tg:")) return null;

        try {
            // 必须是合法 URI
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_PROTOCOLS.contains(scheme.toLowerCase())) {
                return "不支持的链接协议: " + scheme;
            }

            String host = uri.getHost();
            if (host == null) return "无效链接：缺少主机名";

            final String normalizedHost = host.toLowerCase();

            // 禁止 localhost
            if ("localhost".equals(normalizedHost) || "127.0.0.1".equals(normalizedHost) || "[::1]".equals(normalizedHost)) {
                return "禁止提交本地地址链接";
            }

            // 检查是否为 IP 格式的内网地址
            if (isIpFormat(normalizedHost) && isPrivateIpString(normalizedHost)) {
                return "禁止提交内网地址链接";
            }

            // DNS 解析校验：防止域名解析到内网地址（DNS 重绑定防护）
            // 使用独立线程 + 超时，防止 DNS 阻塞请求处理
            if (!isIpFormat(normalizedHost)) {
                try {
                    final String dnsHost = normalizedHost;
                    Future<InetAddress[]> future = DNS_EXECUTOR.submit(() -> InetAddress.getAllByName(dnsHost));
                    InetAddress[] addresses = future.get(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    for (InetAddress addr : addresses) {
                        if (isPrivateInetAddress(addr)) {
                            log.warn("Blocked URL resolving to private IP: {} -> {}", normalizedHost, addr.getHostAddress());
                            return "禁止提交解析到内网地址的链接";
                        }
                    }
                } catch (TimeoutException e) {
                    log.warn("DNS resolution timeout for host: {} ({}ms)", normalizedHost, DNS_TIMEOUT_MS);
                    return "链接域名解析超时";
                } catch (Exception e) {
                    log.debug("DNS resolution failed for host: {}", normalizedHost);
                    return "链接域名解析失败";
                }
            }

            // 基本格式校验
            if (normalizedHost.length() < 3) return "链接域名无效";

            return null;
        } catch (Exception e) {
            return "链接格式无效";
        }
    }

    /** 判断是否为 IP 格式（IPv4） */
    private static boolean isIpFormat(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        try {
            for (String part : parts) {
                int val = Integer.parseInt(part);
                if (val < 0 || val > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 检查字符串形式的 IP 是否为私有地址 */
    private static boolean isPrivateIpString(String ipStr) {
        try {
            String[] parts = ipStr.split("\\.");
            int[] ip = new int[4];
            for (int i = 0; i < 4; i++) {
                ip[i] = Integer.parseInt(parts[i]);
            }
            return isPrivateIpInternal(ip);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 检查 InetAddress 是否为私有/内网地址 */
    private static boolean isPrivateInetAddress(InetAddress addr) {
        if (addr.isLoopbackAddress()) return true;
        if (addr.isSiteLocalAddress()) return true;
        if (addr.isLinkLocalAddress()) return true;
        if (addr.isAnyLocalAddress()) return true;

        // 额外检查一些特殊保留地址
        byte[] bytes = addr.getAddress();
        if (bytes.length == 4) {
            int[] ip = new int[4];
            for (int i = 0; i < 4; i++) {
                ip[i] = bytes[i] & 0xFF;
            }
            return isPrivateIpInternal(ip);
        }
        return false;
    }

    /** 内部方法：检查 IPv4 地址是否为私有/保留地址 */
    private static boolean isPrivateIpInternal(int[] ip) {
        // 127.0.0.0/8 (loopback)
        if (ip[0] == 127) return true;
        // 10.0.0.0/8
        if (ip[0] == 10) return true;
        // 172.16.0.0/12
        if (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31) return true;
        // 192.168.0.0/16
        if (ip[0] == 192 && ip[1] == 168) return true;
        // 169.254.0.0/16 (link-local)
        if (ip[0] == 169 && ip[1] == 254) return true;
        // 0.0.0.0/8
        if (ip[0] == 0) return true;
        // 240.0.0.0/4 (reserved)
        if (ip[0] >= 240) return true;
        return false;
    }
}
