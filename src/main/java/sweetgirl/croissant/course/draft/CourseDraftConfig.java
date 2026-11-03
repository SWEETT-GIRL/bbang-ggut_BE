package sweetgirl.croissant.course.draft;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 초안 자동저장 설정 묶음 — 지금은 스위치(CourseDraftProperties)만. 초안 Redis·플러셔 빈은 뒤 단계에서 여기 모인다. */
@Configuration
@EnableConfigurationProperties(CourseDraftProperties.class)
public class CourseDraftConfig {
	// 빈 정의는 초안 Redis 연결 단계에서 추가된다
}
