# Build stage
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY config config
COPY src src
# spotlessCheck 는 Eclipse 포매터를 download.eclipse.org 에서 받아야 해 외부 장애에 빌드가 묶인다(2026-10-22).
# 포맷은 pre-commit 훅이 로컬에서 spotlessApply 로 맞추고, 스타일 검사는 checkstyle 이 여기서 그대로 돈다.
RUN chmod +x ./gradlew && ./gradlew build -x test -x installGitHooks -x spotlessCheck --no-daemon

# Run stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
