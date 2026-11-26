# Build stage
# 운영 서버(OCI Ampere)는 arm64 인데 러너는 amd64 다. jar 는 아키텍처와 무관하므로 빌드 단계는
# 러너의 아키텍처($BUILDPLATFORM)에서 그대로 돌리고, 실행 단계만 대상 아키텍처로 만든다.
# 이 줄이 없으면 gradle 빌드 전체가 에뮬레이션으로 돌아 몇 배 느려진다.
# Temurin 17 의 alpine 이미지는 amd64 로만 나온다 — arm64 가 있는 Ubuntu(jammy) 기반을 쓴다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk-jammy AS builder
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
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
COPY --from=builder /app/opentelemetry-javaagent.jar opentelemetry-javaagent.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
