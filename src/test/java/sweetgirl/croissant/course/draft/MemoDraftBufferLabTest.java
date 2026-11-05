package sweetgirl.croissant.course.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Lua 5종을 진짜 Redis 로 돈다 — seq 비교·due 계산·꺼내기/임대·compare-and-delete 는 목으로 볼 수 없다.
 * 로컬 Redis(localhost:6379)가 떠 있을 때 {@code ./gradlew test -PlabTests} 로 돈다. 15번 DB 를 쓰고 매번 비운다.
 * 시각은 전부 인자로 넘기므로 기다리지 않는다.
 */
@Tag("lab")
@DisplayName("MemoDraftBuffer (실제 Redis)")
class MemoDraftBufferLabTest {

	private static final long COURSE_ID = 77L;
	private static final long NOW = 1_762_000_000_000L;
	private static final DraftField FIELD = new DraftField(11L, 1L);
	private static final long QUIET_MS = 2_000L;
	private static final long MAX_WAIT_MS = 10_000L;
	private static final long LEASE_MS = 30_000L;

	private MemoDraftRedis redis;
	private StringRedisTemplate template;
	private MemoDraftBuffer buffer;

	@BeforeEach
	void setUp() {
		CourseDraftProperties properties = new CourseDraftProperties();
		properties.getRedis().setMode(CourseDraftProperties.Mode.STANDALONE);
		properties.getRedis().setHost("localhost");
		properties.getRedis().setPort(6379);
		properties.getRedis().setDatabase(15);
		properties.setQuietWindow(Duration.ofMillis(QUIET_MS));
		properties.setMaxWait(Duration.ofMillis(MAX_WAIT_MS));
		properties.setLease(Duration.ofMillis(LEASE_MS));
		redis = MemoDraftRedis.connect(properties.getRedis());
		template = redis.template();
		flushDb();
		buffer = new MemoDraftBuffer(redis, properties);
	}

	@AfterEach
	void tearDown() {
		flushDb();
		redis.close();
	}

	private void flushDb() {
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
	}

	private Double dueScore() {
		return template.opsForZSet().score(MemoDraftBuffer.DUE_KEY, Long.toString(COURSE_ID));
	}

	private Double inflightScore() {
		return template.opsForZSet().score(MemoDraftBuffer.INFLIGHT_KEY, Long.toString(COURSE_ID));
	}

	@Test
	@DisplayName("쓰기: 더 큰 seq 만 받고, 옛 seq 는 무시하며 지금 저장된 seq 를 돌려준다")
	void write_acceptsNewerSeq_ignoresOlder() {
		assertThat(buffer.write(COURSE_ID, FIELD, "소금빵", 10L, NOW)).isEqualTo(10L);
		assertThat(buffer.write(COURSE_ID, FIELD, "늦게 온 글", 5L, NOW + 100)).isEqualTo(10L);
		assertThat(buffer.write(COURSE_ID, FIELD, "같은 seq", 10L, NOW + 200)).isEqualTo(10L);

		List<DraftEntry> entries = buffer.readAll(COURSE_ID);
		assertThat(entries).hasSize(1);
		assertThat(entries.get(0).content()).isEqualTo("소금빵");
		assertThat(entries.get(0).seq()).isEqualTo(10L);
		assertThat(entries.get(0).updatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("due: 마지막 입력 + 2초, 단 첫 입력 + 10초를 넘지 않는다")
	void write_dueFollowsQuietWindowUpToMaxWait() {
		buffer.write(COURSE_ID, FIELD, "a", 1L, NOW);
		assertThat(dueScore()).isEqualTo((double)NOW + QUIET_MS);

		buffer.write(COURSE_ID, FIELD, "ab", 2L, NOW + 1_500);
		assertThat(dueScore()).isEqualTo((double)NOW + 1_500 + QUIET_MS);

		buffer.write(COURSE_ID, FIELD, "abc", 3L, NOW + 9_000);
		assertThat(dueScore()).isEqualTo((double)NOW + MAX_WAIT_MS);
	}

	@Test
	@DisplayName("꺼내기: due 가 안 된 코스는 안 나오고, 꺼낸 코스는 임대로 옮겨져 다시 나오지 않는다")
	void popDue_movesToInflight_withoutDuplicates() {
		buffer.write(COURSE_ID, FIELD, "a", 1L, NOW);

		assertThat(buffer.popDue(NOW + 1_000)).isEmpty();
		assertThat(buffer.popDue(NOW + QUIET_MS)).containsExactly(COURSE_ID);
		assertThat(buffer.popDue(NOW + QUIET_MS)).isEmpty();

		assertThat(dueScore()).isNull();
		assertThat(inflightScore()).isEqualTo((double)NOW + QUIET_MS + LEASE_MS);
		assertThat(template.hasKey(MemoDraftBuffer.metaKey(COURSE_ID))).isFalse();
	}

	@Test
	@DisplayName("반영 중 들어온 새 글: due 에 다시 올리지 않고, release 때 지워지지 않으며 due 로 재등록된다")
	void writeDuringInflight_isKeptAndRequeuedOnRelease() {
		buffer.write(COURSE_ID, FIELD, "a", 1L, NOW);
		buffer.popDue(NOW + QUIET_MS);

		buffer.write(COURSE_ID, FIELD, "ab", 2L, NOW + 2_500);
		assertThat(dueScore()).isNull();

		long releasedAt = NOW + 3_000;
		long remaining = buffer.release(COURSE_ID, Map.of(FIELD, 1L), releasedAt, Duration.ofMillis(QUIET_MS));

		assertThat(remaining).isEqualTo(1);
		assertThat(buffer.readAll(COURSE_ID)).extracting(DraftEntry::seq).containsExactly(2L);
		assertThat(inflightScore()).isNull();
		assertThat(dueScore()).isEqualTo((double)releasedAt + QUIET_MS);
	}

	@Test
	@DisplayName("release: 반영한 seq 와 같은 필드는 지우고, 남은 게 없으면 임대·meta 까지 비운다")
	void release_deletesFlushedFields_andClearsWhenEmpty() {
		buffer.write(COURSE_ID, FIELD, "a", 1L, NOW);
		buffer.popDue(NOW + QUIET_MS);

		long remaining = buffer.release(COURSE_ID, Map.of(FIELD, 1L), NOW + 3_000, Duration.ofMillis(QUIET_MS));

		assertThat(remaining).isZero();
		assertThat(buffer.readAll(COURSE_ID)).isEmpty();
		assertThat(inflightScore()).isNull();
		assertThat(dueScore()).isNull();
		assertThat(template.hasKey(MemoDraftBuffer.metaKey(COURSE_ID))).isFalse();
	}

	@Test
	@DisplayName("release 에 필드 쌍이 없으면(반영 실패) 전부 남기고 재시도 간격 뒤 due 로 돌린다")
	void release_withoutPairs_requeuesEverything() {
		buffer.write(COURSE_ID, FIELD, "a", 1L, NOW);
		buffer.popDue(NOW + QUIET_MS);

		long remaining = buffer.release(COURSE_ID, Map.of(), NOW + 3_000, Duration.ofSeconds(5));

		assertThat(remaining).isEqualTo(1);
		assertThat(inflightScore()).isNull();
		assertThat(dueScore()).isEqualTo((double)NOW + 3_000 + 5_000);
	}

	@Test
	@DisplayName("임대 회수: 만료 전에는 그대로, 만료 뒤엔 due 로 돌아간다")
	void reclaimExpired_returnsExpiredLeasesToDue() {
		buffer.write(COURSE_ID, FIELD, "a", 1L, NOW);
		long poppedAt = NOW + QUIET_MS;
		buffer.popDue(poppedAt);

		assertThat(buffer.reclaimExpired(poppedAt + LEASE_MS - 1)).isZero();
		assertThat(inflightScore()).isNotNull();

		assertThat(buffer.reclaimExpired(poppedAt + LEASE_MS + 1)).isEqualTo(1);
		assertThat(inflightScore()).isNull();
		assertThat(dueScore()).isEqualTo((double)poppedAt + LEASE_MS + 1);
	}

	@Test
	@DisplayName("discard(PATCH 뒤): 그보다 새롭지 않은 초안만 지운다")
	void discard_deletesOnlyWhenNotNewer() {
		buffer.write(COURSE_ID, FIELD, "a", 10L, NOW);

		assertThat(buffer.discard(COURSE_ID, FIELD, 9L)).isFalse();
		assertThat(buffer.readAll(COURSE_ID)).hasSize(1);

		assertThat(buffer.discard(COURSE_ID, FIELD, 10L)).isTrue();
		assertThat(buffer.readAll(COURSE_ID)).isEmpty();
		assertThat(buffer.discard(COURSE_ID, FIELD, 10L)).isFalse();
	}

	@Test
	@DisplayName("readAll: 내용에 ':' 가 있어도, 빈 내용(지우기 예약)이어도 제대로 읽는다")
	void readAll_parsesColonAndBlankContent() {
		DraftField other = new DraftField(12L, 2L);
		buffer.write(COURSE_ID, FIELD, "10:30 도착, 줄 서기", 1L, NOW);
		buffer.write(COURSE_ID, other, "", 2L, NOW);

		List<DraftEntry> entries = buffer.readAll(COURSE_ID);

		assertThat(entries).extracting(DraftEntry::field).containsExactlyInAnyOrder(FIELD, other);
		assertThat(entries).filteredOn(entry -> entry.field().equals(FIELD))
			.extracting(DraftEntry::content).containsExactly("10:30 도착, 줄 서기");
		assertThat(entries).filteredOn(entry -> entry.field().equals(other))
			.allMatch(DraftEntry::isDeletion);
		assertThat(buffer.backlog()).isEqualTo(1);
	}
}
