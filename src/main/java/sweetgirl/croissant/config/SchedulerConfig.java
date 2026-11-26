package sweetgirl.croissant.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

import lombok.extern.slf4j.Slf4j;

/**
 * {@code @Scheduled} 전체의 스위치.
 *
 * <p>같은 운영 데이터를 가진 서버가 둘 뜨는 동안(서버 이전의 사전 검증 등)에는 한쪽의 예약 작업을 꺼야 한다.
 * 중복 실행을 막는 ShedLock 은 DB 에 잠금을 두므로 DB 가 서로 다르면 막아 주지 못하고, 푸시가 두 번 나간다.
 *
 * <p>{@code app.scheduling.enabled=false} 로 끈다. 값을 주지 않으면 켜져 있다 — 평소 동작은 바뀌지 않는다.
 * 끄는 범위는 {@code @Scheduled} 뿐이다. 직접 만든 스케줄러(메모 초안 플러셔 등)와
 * {@code ApplicationReadyEvent} 로 기동 때 한 번 도는 작업은 이 스위치와 무관하다.
 */
@Slf4j
@Configuration
public class SchedulerConfig {

	static final String ENABLED_PROPERTY = "app.scheduling.enabled";

	@Configuration
	@EnableScheduling
	@ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
	static class Enabled {

	}

	/** 꺼진 채로 운영에 남는 일을 막기 위해 기동 때마다 눈에 띄게 남긴다. */
	@Bean
	@ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "false")
	SchedulingDisabledNotice schedulingDisabledNotice() {
		return new SchedulingDisabledNotice();
	}

	static class SchedulingDisabledNotice {

		@EventListener(ApplicationReadyEvent.class)
		public void warn() {
			log.warn("예약 작업(@Scheduled)이 전부 꺼져 있다 — {}=false. 푸시·집계·정리 배치가 돌지 않는다.",
				ENABLED_PROPERTY);
		}
	}
}
