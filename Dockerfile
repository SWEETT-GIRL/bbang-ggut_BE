# Build stage
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY config config
COPY core core
COPY api api
# 멀티 모듈 — 앱 서버 실행 JAR 은 api 모듈의 bootJar 하나다
RUN chmod +x ./gradlew && ./gradlew :api:bootJar -x installGitHooks --no-daemon

# Run stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/api/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
