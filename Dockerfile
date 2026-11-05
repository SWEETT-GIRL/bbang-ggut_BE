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

# OpenTelemetry Java Agent — 이미지에는 항상 들어가지만 JAVA_TOOL_OPTIONS 로 -javaagent 를 줄 때만 켜진다.
# 버전을 올릴 때는 체크섬도 함께 바꾼다(변조·다른 파일을 받으면 빌드가 실패한다).
ENV OTEL_AGENT_VERSION=2.32.0
ENV OTEL_AGENT_SHA256=f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82
RUN wget -q -O /app/opentelemetry-javaagent.jar \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar" \
    && echo "${OTEL_AGENT_SHA256}  /app/opentelemetry-javaagent.jar" | sha256sum -c -

# Run stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
COPY --from=builder /app/opentelemetry-javaagent.jar opentelemetry-javaagent.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
