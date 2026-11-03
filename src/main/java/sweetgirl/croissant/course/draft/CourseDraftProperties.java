package sweetgirl.croissant.course.draft;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code course.draft.*} — 빵투어 메모 초안 자동저장(Redis 버퍼 + STOMP) 스위치.
 *
 * <p>{@code write-enabled}: PUT memo-draft 를 받을지. 꺼지면 503 → 앱은 지금처럼 PATCH 만 쓴다. 상세 응답의 {@code draftEnabled} 가 이 값이다.
 * 운영에는 아직 Redis 가 없어 기본 false 다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "course.draft")
public class CourseDraftProperties {

	private boolean writeEnabled = false;
}
