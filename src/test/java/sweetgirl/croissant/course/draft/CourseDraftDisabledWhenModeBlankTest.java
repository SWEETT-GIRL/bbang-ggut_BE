package sweetgirl.croissant.course.draft;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.course.signal.WebSocketConfig;
import sweetgirl.croissant.external.aws.S3Service;

/**
 * compose 가 {@code COURSE_DRAFT_REDIS_MODE: ${...:-}} 로 빈 값을 넘기는 환경 — 초안·소켓 빈이 하나도 떠서는 안 된다.
 * 빈 mode 는 테스트 공용 application.properties 에 있어 기본 컨텍스트를 그대로 쓴다(컨텍스트를 하나 더 만들면 CI 메모리가 터진다).
 * {@code @ConditionalOnProperty} 였다면 빈 문자열도 "있다"로 봐서 없는 Redis 를 1초마다 찔렀을 것이다.
 */
@SpringBootTest
@DisplayName("초안 Redis mode 가 빈 값인 컨텍스트")
class CourseDraftDisabledWhenModeBlankTest {

	@MockitoBean
	S3Service s3Service;

	@Autowired
	ApplicationContext context;

	@Test
	@DisplayName("초안·소켓 빈이 전부 빠지고 draftEnabled 는 false 다 — 쓰기 스위치가 켜져 있어도")
	void draftBeansAbsent() {
		assertThat(context.getBeansOfType(MemoDraftRedis.class)).isEmpty();
		assertThat(context.getBeansOfType(MemoDraftBuffer.class)).isEmpty();
		assertThat(context.getBeansOfType(MemoDraftFlusher.class)).isEmpty();
		assertThat(context.getBeansOfType(MemoDraftController.class)).isEmpty();
		assertThat(context.getBeansOfType(WebSocketConfig.class)).isEmpty();
		assertThat(context.getBean(CourseDraftProperties.class).isDraftEnabled()).isFalse();
	}
}
