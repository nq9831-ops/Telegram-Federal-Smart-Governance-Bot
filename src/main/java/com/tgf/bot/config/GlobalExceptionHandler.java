package com.tgf.bot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * GlobalExceptionHandler — 全局异常处理器。
 * 所有 Controller 未捕获的异常统一在这里处理，防止敏感信息泄露给客户端。
 * @since 1.0
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(Map.of(
            "success", false, "message", "缺少必要参数: " + e.getParameterName()
        ));
    }

    @ExceptionHandler(NumberFormatException.class)
    public ResponseEntity<Map<String, Object>> handleNumberFormat(NumberFormatException e) {
        return ResponseEntity.badRequest().body(Map.of(
            "success", false, "message", "参数格式错误"
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        // 记录详细日志，但不直接暴露给前端（防止信息泄露）
        log.warn("Illegal argument: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
            "success", false, "message", "请求参数无效"
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("Unhandled exception: {}", e.getMessage(), e);
        // 不暴露任何内部错误信息给前端
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "success", false, "message", "服务器内部错误，请稍后重试"
        ));
    }
}
