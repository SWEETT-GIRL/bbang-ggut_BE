package sweetgirl.croissant.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

@ExtendWith(MockitoExtension.class)
class BatchJobRunnerTest {

	@Mock
	private BatchJobRegistry registry;

	@Test
	@DisplayName("받은 이름의 작업을 한 번 실행한다")
	void runsNamedJob() {
		AtomicInteger calls = new AtomicInteger();
		given(registry.find("ranking-snapshot")).willReturn(Optional.of(calls::incrementAndGet));

		new BatchJobRunner(registry, "ranking-snapshot").run(new DefaultApplicationArguments());

		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("모르는 이름이면 가능한 작업 목록과 함께 실패한다 — 종료 코드로 Lambda 가 알 수 있다")
	void unknownJobFails() {
		given(registry.find("typo")).willReturn(Optional.empty());
		given(registry.jobs()).willReturn(Map.of("ranking-snapshot", mock(Runnable.class)));

		assertThatThrownBy(() -> new BatchJobRunner(registry, "typo").run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("typo")
			.hasMessageContaining("ranking-snapshot");
	}

	@Test
	@DisplayName("작업이 던진 예외는 그대로 올려 실패로 끝낸다")
	void jobFailurePropagates() {
		given(registry.find("ranking-snapshot")).willReturn(Optional.of(() -> {
			throw new IllegalStateException("db down");
		}));

		assertThatThrownBy(
			() -> new BatchJobRunner(registry, "ranking-snapshot").run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("db down");
	}
}
