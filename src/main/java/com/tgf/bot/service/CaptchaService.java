package com.tgf.bot.service;

import com.google.code.kaptcha.impl.DefaultKaptcha;
import com.google.code.kaptcha.util.Config;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * CaptchaService — 验证码服务。
 * 
 * 基于 Kaptcha 生成图形验证码，存储到 Redis 并支持校验。
 * @since 1.0
 */
@Service
public class CaptchaService {

    private final DefaultKaptcha captchaProducer;
    private final StringRedisTemplate redis;
    private static final String KEY_PREFIX = "captcha:";

    public CaptchaService(StringRedisTemplate redis) {
        this.redis = redis;

        DefaultKaptcha kaptcha = new DefaultKaptcha();
        java.util.Properties props = new java.util.Properties();
        props.setProperty("kaptcha.border", "no");
        props.setProperty("kaptcha.textproducer.font.color", "black");
        props.setProperty("kaptcha.textproducer.char.space", "5");
        props.setProperty("kaptcha.textproducer.char.length", "4");
        props.setProperty("kaptcha.image.width", "200");
        props.setProperty("kaptcha.image.height", "60");
        props.setProperty("kaptcha.obscurificator.impl", "com.google.code.kaptcha.impl.ShadowGimpy");
        props.setProperty("kaptcha.noise.impl", "com.google.code.kaptcha.impl.DefaultNoise");
        props.setProperty("kaptcha.textproducer.font.names", "Arial,Courier");
        props.setProperty("kaptcha.textproducer.font.size", "40");
        kaptcha.setConfig(new Config(props));
        this.captchaProducer = kaptcha;
    }

    public record CaptchaResult(String captchaId, String imageBase64) {}

    /** 生成图形验证码 */
    public CaptchaResult generate() {
        String text = captchaProducer.createText();
        BufferedImage image = captchaProducer.createImage(text);
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        redis.opsForValue().set(KEY_PREFIX + id, text, 5, TimeUnit.MINUTES);

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            return new CaptchaResult(id, "data:image/png;base64," + base64);
        } catch (Exception e) {
            return new CaptchaResult(id, "");
        }
    }

    /** 生成算术验证码（入群验证用） */
    public record ArithmeticCaptcha(String captchaId, String question, int expectedAnswer) {}

    public ArithmeticCaptcha generateArithmetic() {
        int a = (int)(Math.random() * 10) + 1;
        int b = (int)(Math.random() * 10) + 1;
        String op = Math.random() > 0.5 ? "+" : "-";
        if ("-".equals(op) && a < b) { int t = a; a = b; b = t; }
        int answer = "+".equals(op) ? a + b : a - b;
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        redis.opsForValue().set("captcha_arith:" + id, String.valueOf(answer), 5, TimeUnit.MINUTES);
        return new ArithmeticCaptcha(id, a + " " + op + " " + b + " = ?", answer);
    }

    public boolean verifyArithmetic(String captchaId, int input) {
        String key = "captcha_arith:" + captchaId;
        String expected = redis.opsForValue().get(key);
        if (expected == null) return false;
        redis.delete(key);
        try {
            return Integer.parseInt(expected) == input;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 校验图形验证码 */
    public boolean verify(String captchaId, String input) {
        String key = KEY_PREFIX + captchaId;
        String expected = redis.opsForValue().get(key);
        if (expected == null) return false;
        redis.delete(key);
        return expected.equalsIgnoreCase(input.trim());
    }
}
