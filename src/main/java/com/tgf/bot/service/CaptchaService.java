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
        // 无边框
        props.setProperty("kaptcha.border", "no");
        // 字体颜色：深灰色（比纯黑更难OCR）
        props.setProperty("kaptcha.textproducer.font.color", "35,35,35");
        // 字符间距（增大，增加识别难度）
        props.setProperty("kaptcha.textproducer.char.space", "8");
        // 字符长度：从 4 位增加到 5 位
        props.setProperty("kaptcha.textproducer.char.length", "5");
        // 图片尺寸
        props.setProperty("kaptcha.image.width", "220");
        props.setProperty("kaptcha.image.height", "70");
        // 干扰样式：使用更复杂的 FishEyeGimpy（鱼眼变形）
        props.setProperty("kaptcha.obscurificator.impl", "com.google.code.kaptcha.impl.FishEyeGimpy");
        // 噪点：使用更强的噪点
        props.setProperty("kaptcha.noise.impl", "com.google.code.kaptcha.impl.DefaultNoise");
        props.setProperty("kaptcha.noise.color", "100,100,100");
        // 字体：多种字体混合
        props.setProperty("kaptcha.textproducer.font.names", "Arial,Courier,Georgia,Verdana");
        // 字体大小
        props.setProperty("kaptcha.textproducer.font.size", "42");
        // 字符范围：去掉易混淆字符（0/O, 1/l/I）
        props.setProperty("kaptcha.textproducer.char.string", "23456789ABCDEFGHJKLMNPQRSTUVWXYZ");
        // 背景：从白色改为浅灰色渐变，增加识别难度
        props.setProperty("kaptcha.background.clear.from", "245,245,245");
        props.setProperty("kaptcha.background.clear.to", "255,255,255");
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
