# ===== 构建阶段 =====
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# 1. 复制 pom.xml 缓存依赖
COPY pom.xml .
RUN mvn dependency:go-offline -q 2>/dev/null || true

# 2. 复制源码并编译
COPY src src/
RUN mvn package -DskipTests -q && \
    mv target/tg-federal-bot-*.jar app.jar

# ===== 运行阶段 =====
FROM eclipse-temurin:21-jre-alpine

# 安装 curl 用于健康检查
RUN apk add --no-cache curl

# 创建非 root 用户
RUN addgroup -S tgf && adduser -S tgf -G tgf

WORKDIR /app

# 复制编译好的 jar
COPY --from=builder /build/app.jar app.jar

RUN chown -R tgf:tgf /app

USER tgf

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
