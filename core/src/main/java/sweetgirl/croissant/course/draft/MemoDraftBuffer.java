package sweetgirl.croissant.course.draft;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 메모 초안 버퍼 — 초안 전용 Redis 위의 자료구조와 Lua 5종.
 *
 * <ul>
 * <li>{@code draft:{courseId}} Hash: 필드 {@code storeId:memberId} → {@code seq:updatedAt:content}. 코스 단위로 한 번에 읽는다.</li>
 * <li>{@code draft:meta:{courseId}} Hash: {@code firstDirtyAt} — 첫 미반영 입력 시각. "10초 상한"의 기준.</li>
 * <li>{@code draft:due} ZSET: 멤버 courseId, score 반영 예정 시각(ms).</li>
 * <li>{@code draft:inflight} ZSET: 멤버 courseId, score 임대 만료 시각. 플러셔가 꺼낸 코스가 여기 있다.</li>
 * </ul>
 *
 * <p>비교와 갱신은 전부 Lua 안에서 한 번에 한다 — 쪼개면 경합에서 seq 가 역전된다. 모든 메서드는 Redis 장애를
 * {@link org.springframework.dao.DataAccessException} 으로 그대로 올린다. 부르는 쪽이 503 으로 바꾸거나 다음 틱으로 미룬다.
 */
@Slf4j
@Component
@ConditionalOnExpression(CourseDraftConfig.REDIS_CONFIGURED)
public class MemoDraftBuffer {

	static final String DUE_KEY = "draft:due";
	static final String INFLIGHT_KEY = "draft:inflight";
	static final String META_PREFIX = "draft:meta:";

	private static final RedisScript<Long> WRITE = script("draft-write.lua", Long.class);
	private static final RedisScript<List> POP = script("draft-pop.lua", List.class);
	private static final RedisScript<Long> RELEASE = script("draft-release.lua", Long.class);
	private static final RedisScript<Long> RECLAIM = script("draft-reclaim.lua", Long.class);
	private static final RedisScript<Long> DISCARD = script("draft-discard.lua", Long.class);

	private final MemoDraftRedis memoDraftRedis;
	private final StringRedisTemplate redis;
	private final CourseDraftProperties properties;

	public MemoDraftBuffer(MemoDraftRedis memoDraftRedis, CourseDraftProperties properties) {
		this.memoDraftRedis = memoDraftRedis;
		this.redis = memoDraftRedis.template();
		this.properties = properties;
	}

	private static <T> RedisScript<T> script(String name, Class<T> resultType) {
		DefaultRedisScript<T> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("redis/" + name));
		script.setResultType(resultType);
		return script;
	}

	static String courseKey(long courseId) {
		return "draft:{" + courseId + "}";
	}

	static String metaKey(long courseId) {
		return META_PREFIX + courseId;
	}

	/**
	 * 초안을 쓴다. 저장된 seq 보다 큰 글만 받고, 코스의 반영 예정 시각을 {@code min(now + quietWindow, firstDirtyAt + maxWait)} 로 둔다.
	 * 플러셔가 이미 꺼내 든 코스(inflight)면 due 에 다시 넣지 않는다 — 반영 뒤 {@link #release} 가 남은 걸 보고 재등록한다.
	 *
	 * @return 받아들여진 seq. 옛 글이었으면 지금 저장돼 있는 seq
	 */
	public long write(long courseId, DraftField field, String content, long seq, long nowMs) {
		Long accepted;
		try {
			accepted = redis.execute(WRITE,
				List.of(courseKey(courseId), metaKey(courseId), DUE_KEY, INFLIGHT_KEY),
				field.key(), Long.toString(seq), Long.toString(nowMs), content,
				Long.toString(properties.getQuietWindow().toMillis()),
				Long.toString(properties.getMaxWait().toMillis()),
				Long.toString(properties.getTtl().toMillis()), Long.toString(courseId));
		} catch (QueryTimeoutException exception) {
			// 멈춘 Redis 에 붙어 있을 수 있다 — 연달아 나면 연결을 다시 맺는다
			memoDraftRedis.commandTimedOut();
			throw exception;
		}
		memoDraftRedis.commandSucceeded();
		return accepted == null ? seq : accepted;
	}

	/** 반영 시각이 된 코스를 꺼내 임대(inflight) 로 옮긴다. 여러 인스턴스가 동시에 불러도 같은 코스가 두 번 나오지 않는다. */
	@SuppressWarnings("unchecked")
	public List<Long> popDue(long nowMs) {
		List<Object> ids = redis.execute(POP, List.of(DUE_KEY, INFLIGHT_KEY),
			Long.toString(nowMs), Long.toString(properties.getLease().toMillis()),
			Integer.toString(properties.getFlushBatchSize()), META_PREFIX);
		if (ids == null) {
			return List.of();
		}
		List<Long> courseIds = new ArrayList<>(ids.size());
		for (Object id : ids) {
			courseIds.add(Long.parseLong(id.toString()));
		}
		return courseIds;
	}

	/** 코스의 초안 전부. 형식이 깨진 필드는 건너뛰고 로그만 남긴다 — 하나 때문에 코스 전체 반영이 막히면 안 된다. */
	public List<DraftEntry> readAll(long courseId) {
		Map<Object, Object> raw = redis.opsForHash().entries(courseKey(courseId));
		List<DraftEntry> entries = new ArrayList<>(raw.size());
		raw.forEach((fieldKey, value) -> {
			try {
				entries.add(DraftEntry.decode(fieldKey.toString(), value.toString()));
			} catch (IllegalArgumentException exception) {
				log.warn("[메모 초안] 깨진 초안을 건너뛴다 courseId={} field={} cause={}", courseId, fieldKey,
					exception.getMessage());
			}
		});
		return entries;
	}

	/**
	 * 반영을 끝낸 코스를 놓는다. 넘긴 필드 중 저장된 seq 가 반영한 seq 이하인 것만 지운다(반영 중 들어온 새 글은 남는다).
	 * 임대를 풀고, 남은 초안이 있으면 {@code now + redueDelay} 에 다시 반영하도록 due 에 올린다.
	 * 반영이 실패했으면 빈 맵으로 불러 전부 남기고 재시도 간격만 준다.
	 *
	 * @return 남은 초안 수
	 */
	public long release(long courseId, Map<DraftField, Long> flushedSeqs, long nowMs, Duration redueDelay) {
		List<String> args = new ArrayList<>(3 + flushedSeqs.size() * 2);
		args.add(Long.toString(courseId));
		args.add(Long.toString(nowMs));
		args.add(Long.toString(redueDelay.toMillis()));
		flushedSeqs.forEach((field, seq) -> {
			args.add(field.key());
			args.add(Long.toString(seq));
		});
		Long remaining = redis.execute(RELEASE,
			List.of(courseKey(courseId), metaKey(courseId), DUE_KEY, INFLIGHT_KEY),
			args.toArray());
		return remaining == null ? 0 : remaining;
	}

	/** 임대가 만료된 코스(죽은 플러셔가 들고 있던 것)를 due 로 되돌린다. */
	public long reclaimExpired(long nowMs) {
		Long reclaimed = redis.execute(RECLAIM, List.of(INFLIGHT_KEY, DUE_KEY), Long.toString(nowMs));
		return reclaimed == null ? 0 : reclaimed;
	}

	/** PATCH 가 같은 칸을 DB 에 바로 반영한 뒤 — 그보다 새롭지 않은 초안은 지운다. due·inflight 는 건드리지 않는다. */
	public boolean discard(long courseId, DraftField field, long seq) {
		Long deleted = redis.execute(DISCARD, List.of(courseKey(courseId)), field.key(), Long.toString(seq));
		return deleted != null && deleted > 0;
	}

	/** 반영을 기다리는 코스 수 — 게이지·알림용. */
	public long backlog() {
		Long size = redis.opsForZSet().zCard(DUE_KEY);
		return size == null ? 0 : size;
	}
}
