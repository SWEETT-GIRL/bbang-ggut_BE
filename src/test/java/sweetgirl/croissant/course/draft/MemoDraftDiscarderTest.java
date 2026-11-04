package sweetgirl.croissant.course.draft;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoDraftDiscarder")
class MemoDraftDiscarderTest {

	@Mock
	private MemoDraftBuffer buffer;

	@Test
	@DisplayName("초안 Redis 가 없는 환경에서는 아무것도 하지 않는다 — PATCH 는 그대로 돌아야 한다")
	void withoutBuffer_noop() {
		MemoDraftDiscarder discarder = new MemoDraftDiscarder(Optional.empty());

		assertThatCode(() -> discarder.discardAfterCommit(10L, 100L, 1L, 5L)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("트랜잭션이 없으면 바로 지운다")
	void withoutTransaction_discardsImmediately() {
		MemoDraftDiscarder discarder = new MemoDraftDiscarder(Optional.of(buffer));

		discarder.discardAfterCommit(10L, 100L, 1L, 5L);

		then(buffer).should().discard(10L, new DraftField(100L, 1L), 5L);
	}

	@Test
	@DisplayName("Redis 가 안 돼도 PATCH 는 성공이다 — 플러셔가 seq 로 거른다")
	void redisFailure_swallowed() {
		MemoDraftDiscarder discarder = new MemoDraftDiscarder(Optional.of(buffer));
		given(buffer.discard(10L, new DraftField(100L, 1L), 5L)).willThrow(new RedisConnectionFailureException("down"));

		assertThatCode(() -> discarder.discardAfterCommit(10L, 100L, 1L, 5L)).doesNotThrowAnyException();
	}
}
