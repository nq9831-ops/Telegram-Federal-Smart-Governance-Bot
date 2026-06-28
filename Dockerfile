# ===== 构建阶段 =====
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# 1. 先复制 pom.xml 缓存依赖（层缓存优化）
COPY pom.xml .
RUN mvn dependency:go-offline -q 2>/dev/null || true

# 2. 复制源码并编译
COPY src src/
RUN mvn package -DskipTests -q && \
    mv target/tg-federal-bot-*.jar app.jar

# ===== 运行阶段 =====
FROM eclipse-temurin:21-jre-alpine

# 创建非 root 用户
RUN addgroup -S tgf && adduser -S tgf -G tgf

WORKDIR /app

# 只复制编译好的 jar
COPY --from=builder /build/app.jar app.jar

# ⚠️ 配置文件不打包进镜像！
# 通过以下方式之一注入：
#   - docker-compose: volumes 挂载
#   - 环境变量 ENV
#   - Docker secrets

RUN chown -R tgf:tgf /app

USER tgf

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
