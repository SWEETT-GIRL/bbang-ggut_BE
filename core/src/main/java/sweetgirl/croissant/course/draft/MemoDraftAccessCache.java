package sweetgirl.croissant.course.draft;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.service.CourseAccessPolicy;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;

/**
 * PUT memo-draft 의 "이 사람이 이 코스에 메모를 써도 되는가" — 편집 권한(방장·활성 멤버, 큐레이션 제외) 과 쓰기 제한(밴).
 *
 * <p>0.5초마다 오는 요청마다 DB 를 읽지 않도록 통과한 판정만 10초 들고 있다. 거절은 캐시하지 않는다 — 초대를 막 수락한 사람이
 * 10초 동안 막히면 안 되고, 거절을 반복하는 요청은 레이트리밋이 막는다. 틀리는 방향은 하나다: 나간 사람·방금 밴된 사람의 초안이
 * 최대 10초 Redis 에 머문다. 플러셔가 반영 직전에 다시 판정해 버린다.
 *
 * <p><b>DB 가 죽어도 쓰던 사람은 계속 쓴다.</b> 이 버퍼는 "DB 가 바빠도 타자는 받는다"가 목적인데, DB 를 60초 멈추니 PUT 이
 * 6초 만에 전부 멈췄다(2026-11-11 측정: 정지 중 받은 글이 평소의 6%). 캐시가 빠진 요청이 커넥션을 3초씩 기다리며 요청 스레드를
 * 잡았고, 그런 요청이 초당 백 건씩 생겨 스레드가 바닥나 캐시가 살아 있는 요청까지 줄을 섰다. 그래서:
 * <ul>
 * <li>DB 판정이 실패하면 {@code access-db-backoff} 동안은 DB 를 다시 두드리지 않는다. 그 뒤에도 한 스레드만 확인하러 간다.</li>
 * <li>그동안은 마지막으로 통과했던 판정({@code access-stale-grace} 안쪽)을 인정한다. 이력이 없으면 바로 실패(→ 503)한다.</li>
 * </ul>
 * DB 가 돌아온 직후에는 미뤄 둔 갱신이 한꺼번에 몰린다(복귀 뒤 2초간 처리량 2,500 → 1,054건/초, Hikari 대기 389 — 같은 측정).
 * 그래서 복귀 뒤 캐시 TTL 한 번 동안은 마지막 판정으로 캐시를 되채우고, 갱신은 캐시 만료의 지터에 맡겨 흩는다.
 *
 * <p>틀리는 방향은 캐시와 같다 — DB 가 죽어 있는 동안 나간 사람의 초안이 Redis 에 머물 수 있고, 반영 때 다시 판정해 버린다.
 */
@Slf4j
@Component
@ConditionalOnExpression(CourseDraftConfig.REDIS_CONFIGURED)
public class MemoDraftAccessCache {

	private final CourseRepository courseRepository;
	private final CourseAccessPolicy courseAccessPolicy;
	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;
	private final Cache<String, Boolean> allowed;
	/** DB 가 죽었을 때 인정할 "마지막으로 통과한 판정". 거절되면 지운다 */
	private final Cache<String, Boolean> lastAllowed;
	private final Ticker ticker;
	private final long dbBackoffNanos;
	private final Counter staleServed;
	private volatile boolean dbDown;
	private volatile long dbRetryAtNanos;
	/** DB 가 돌아온 직후 이 시각까지는 미뤄 둔 갱신을 DB 로 보내지 않는다. 0 이면 복귀 중이 아니다 */
	private volatile long recoveringUntilNanos;
	private final long recoverySpreadNanos;
	private final AtomicBoolean probing = new AtomicBoolean();

	/** 생성자가 둘(테스트용 Ticker 주입)이라 Spring 이 쓸 쪽을 가리킨다 — 없으면 기본 생성자를 찾다 죽는다. */
	@Autowired
	public MemoDraftAccessCache(CourseRepository courseRepository, CourseAccessPolicy courseAccessPolicy,
			MemberReader memberReader, MemberRestrictionReader memberRestrictionReader,
			CourseDraftProperties properties, MeterRegistry meterRegistry) {
		this(courseRepository, courseAccessPolicy, memberReader, memberRestrictionReader, properties,
			meterRegistry, Ticker.systemTicker());
	}

	MemoDraftAccessCache(CourseRepository courseRepository, CourseAccessPolicy courseAccessPolicy,
			MemberReader memberReader, MemberRestrictionReader memberRestrictionReader,
			CourseDraftProperties properties, MeterRegistry meterRegistry,
			Ticker ticker) {
		this.courseRepository = courseRepository;
		this.courseAccessPolicy = courseAccessPolicy;
		this.memberReader = memberReader;
		this.memberRestrictionReader = memberRestrictionReader;
		this.ticker = ticker;
		this.dbBackoffNanos = properties.getAccessDbBackoff().toNanos();
		// 복귀 직후 한 번에 몰리는 것은 "그동안 만료된 캐시 전부"다. 캐시 TTL 한 번만큼만 미루면 그 뒤는 평소의 만료 속도가 된다
		this.recoverySpreadNanos = properties.getAccessCacheTtl().toNanos();
		this.staleServed = Counter.builder("draft_access_stale_total")
			.description("DB 를 못 읽어 마지막 판정으로 통과시킨 초안 PUT 수").register(meterRegistry);
		this.lastAllowed = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofNanos(properties.getAccessStaleGrace().toNanos()))
			.maximumSize(100_000)
			.ticker(ticker)
			.build();
		// 만료에 지터(TTL ~ 1.5 TTL)를 준다 — 320명이 같은 시각에 시작하면 캐시가 한꺼풀에 빠져 1,000개 넘는 쿼리가 같은 초에
		// 풀 10개로 몰렸다(2026-11-04 dev 측정, PUT 500). 만료 시각을 흩어 놓으면 초당 미스가 평평해진다
		long ttlNanos = properties.getAccessCacheTtl().toNanos();
		this.allowed = Caffeine.newBuilder()
			.expireAfter(new Expiry<String, Boolean>() {
				@Override
				public long expireAfterCreate(String key, Boolean value, long currentTime) {
					return ttlNanos + ThreadLocalRandom.current().nextLong(ttlNanos / 2 + 1);
				}

				@Override
				public long expireAfterUpdate(String key, Boolean value, long currentTime, long currentDuration) {
					return expireAfterCreate(key, value, currentTime);
				}

				@Override
				public long expireAfterRead(String key, Boolean value, long currentTime, long currentDuration) {
					return currentDuration;
				}
			})
			.maximumSize(100_000)
			.ticker(ticker)
			.build();
	}

	/**
	 * 못 쓰면 던진다 — 게스트 403, 코스 없음 404, 권한 없음 403, 쓰기 제한 403.
	 * 로그인 판정(게스트 여부)도 이 안에서 한다 — 밖에서 매 요청 부르면 그것만으로 요청마다 DB 를 한 번 읽어
	 * 320명 × 초당 2회에서 커넥션 풀이 마른다(2026-11-04 dev 측정에서 발견, Hikari pending 125).
	 */
	public void requireEditable(Long courseId, Long memberId) {
		String key = courseId + ":" + memberId;
		if (Boolean.TRUE.equals(allowed.getIfPresent(key))) {
			return;
		}
		if (dbDown && !mayProbe()) {
			allowFromLastDecision(key, null);
			return;
		}
		if (recoveringUntilNanos != 0 && ticker.read() < recoveringUntilNanos
			&& Boolean.TRUE.equals(lastAllowed.getIfPresent(key))) {
			// DB 가 막 돌아왔다 — 미뤄 둔 갱신을 한꺼번에 DB 로 보내지 않고, 마지막 판정으로 캐시에 되돌려 넣는다.
			// 캐시 만료에 지터가 있어 다음 갱신은 TTL ~ 1.5 TTL 에 걸쳐 흩어진다
			allowed.put(key, Boolean.TRUE);
			staleServed.increment();
			return;
		}
		try {
			memberReader.requireLoggedIn(memberId);
			Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
				.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
			courseAccessPolicy.requireEditable(course, memberId);
			// 초안은 DRAFT 신호로 일행에게 바로 보이는 글이다 — PATCH 의 메모 저장과 같은 문을 지난다
			memberRestrictionReader.validateCanWrite(memberId);
		} catch (DataAccessException | TransactionException exception) {
			if (!dbDown) {
				log.warn("[메모 초안] 권한 판정 DB 실패 — {} 동안 DB 를 두드리지 않고 마지막 판정으로 받는다 cause={}",
					Duration.ofNanos(dbBackoffNanos), exception.toString());
			}
			dbRetryAtNanos = ticker.read() + dbBackoffNanos;
			dbDown = true;
			probing.set(false);
			allowFromLastDecision(key, exception);
			return;
		} catch (RuntimeException denied) {
			// DB 는 답했다 — 거절이다. 다음 장애 때 옛 통과로 되살아나지 않게 지운다
			recovered();
			lastAllowed.invalidate(key);
			throw denied;
		}
		recovered();
		allowed.put(key, Boolean.TRUE);
		lastAllowed.put(key, Boolean.TRUE);
	}

	/** DB 가 죽은 걸로 보는 동안 — 미룬 시간이 지났고, 확인하러 간 스레드가 아직 없을 때만 한 스레드가 DB 를 두드린다. */
	private boolean mayProbe() {
		return ticker.read() >= dbRetryAtNanos && probing.compareAndSet(false, true);
	}

	private void recovered() {
		if (dbDown) {
			log.info("[메모 초안] 권한 판정 DB 복귀 — {} 동안은 마지막 판정으로 캐시를 채워 갱신을 흩는다",
				Duration.ofNanos(recoverySpreadNanos));
			recoveringUntilNanos = ticker.read() + recoverySpreadNanos;
		}
		dbDown = false;
		probing.set(false);
	}

	private void allowFromLastDecision(String key, RuntimeException cause) {
		if (Boolean.TRUE.equals(lastAllowed.getIfPresent(key))) {
			staleServed.increment();
			return;
		}
		throw cause != null ? cause : new DataAccessResourceFailureException("권한 판정 DB 를 쓸 수 없고 이전 판정도 없다");
	}
}
