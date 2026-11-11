package sweetgirl.croissant.course.draft;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code course.draft.*} — 빵투어 메모 초안 자동저장(Redis 버퍼 + STOMP).
 *
 * <p>{@code redis.mode} 가 비어 있으면 초안 기능 전체(버퍼·플러셔·신호·API)가 뜨지 않는다 — 운영에는 아직 Redis 가 없다.
 * {@code write-enabled} 는 PUT memo-draft 를 받을지. 꺼지면 503 → 앱은 지금처럼 PATCH 만 쓴다. 상세 응답 {@code draftEnabled} 는
 * 둘을 합친 {@link #isDraftEnabled()} 다. {@code flusher-enabled} 는 쓰기를 꺼도 Redis 에 남은 초안을 계속 비우기 위해 따로 둔다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "course.draft")
public class CourseDraftProperties {

	private boolean writeEnabled = false;

	private boolean flusherEnabled = true;

	/** 코스 기준 마지막 입력 뒤 이만큼 조용하면 DB 에 반영한다. */
	private Duration quietWindow = Duration.ofSeconds(2);

	/** 첫 미반영 입력 뒤 이만큼 지나면 계속 치는 사람이 있어도 반영한다. */
	private Duration maxWait = Duration.ofSeconds(10);

	/**
	 * 플러셔가 코스를 꺼내 들고 있을 수 있는 시간. 지나면 죽은 플러셔로 보고 다른 인스턴스가 회수한다.
	 * 반영 한 번은 1초 안쪽이라 짧아도 된다 — 30초일 때 페일오버 뒤 마지막 글이 DB 에 가기까지 17~39초(11/5), 15초일 때
	 * 14~22초(11/9 운영)가 걸렸고 그 대부분이 이 임대를 기다린 시간이다. 임대가 지나 다른 플러셔가 같은 코스를 다시 잡아도
	 * 코스 행 락과 seq 비교가 중복 반영을 막는다.
	 */
	private Duration lease = Duration.ofSeconds(5);

	/** DB 반영이 실패한 코스를 다시 시도하기까지. */
	private Duration retryDelay = Duration.ofSeconds(5);

	/** 코스 Hash 의 TTL — 고아 초안 정리용 안전장치. 정상 경로에서는 플러셔가 훨씬 먼저 비운다. */
	private Duration ttl = Duration.ofDays(7);

	/** 한 틱에 꺼내는 코스 수. */
	private int flushBatchSize = 20;

	/** 회원당 초당 PUT 허용 횟수. 넘으면 429. */
	private int ratePerSecond = 10;

	/**
	 * 인스턴스 하나가 동시에 처리하는 PUT 수의 상한. 넘치면 기다리지 않고 429 — 받은 요청은 평소 속도로 끝난다.
	 * 0 이하면 상한 없음(기본). 앱이 429 에 0.5초 뒤 바로 재시도하는 동안은 켜지 않는다 — 거절이 재요청이 되어 처리량이
	 * 준다({@link MemoDraftLoadShedFilter}).
	 */
	private int maxConcurrentWrites = 0;

	/** PUT 의 권한·쓰기 제한 판정을 들고 있는 시간. 틀려도 "나간 사람 초안이 이만큼 Redis 에 머무는" 방향뿐이다. */
	private Duration accessCacheTtl = Duration.ofSeconds(10);

	/** DB 를 못 읽을 때, 마지막으로 통과한 판정을 이만큼 지난 것까지 인정한다. 이보다 오래 DB 가 죽어 있으면 503. */
	private Duration accessStaleGrace = Duration.ofMinutes(5);

	/** 권한 판정 DB 가 실패한 뒤 다시 두드리기까지. 그동안 캐시가 빠진 요청은 커넥션을 기다리지 않는다. */
	private Duration accessDbBackoff = Duration.ofSeconds(2);

	private Redis redis = new Redis();

	public boolean isRedisConfigured() {
		return redis.getMode() != null;
	}

	/** 상세 응답 {@code draftEnabled} — 앱이 초안 경로를 쓸지 가르는 값. */
	public boolean isDraftEnabled() {
		return writeEnabled && isRedisConfigured();
	}

	/** 초안 전용 Redis. 캐시 Redis(allkeys-lru) 와 분리한다 — 거기 두면 메모리 압박 때 LRU 가 초안을 지운다. */
	@Getter
	@Setter
	public static class Redis {

		/** 비어 있으면 초안 기능 전체가 뜨지 않는다. */
		private Mode mode;

		private String host = "localhost";

		private int port = 6379;

		/** 로컬에서 캐시 Redis 와 한 인스턴스를 나눠 쓸 때 번호로 가른다. */
		private int database = 0;

		private String password;

		/** SENTINEL 모드: 감시하는 마스터 이름. */
		private String sentinelMaster = "draft";

		/** SENTINEL 모드: {@code host:port} 목록. */
		private List<String> sentinelNodes = List.of();

		/**
		 * 200ms 였는데 2vCPU dev 박스에서 320명 부하 때 Redis command timed out 이 1초에 수십 건 났다(2026-11-04).
		 * 쓰기는 Lua 한 번이라 1초면 넉넉하고, 넘기면 503 → 앱이 PATCH 로 물러난다.
		 */
		private Duration commandTimeout = Duration.ofSeconds(1);

		private Duration connectTimeout = Duration.ofMillis(500);

		/**
		 * 연결이 끊긴 뒤 다시 붙기를 시도하는 간격(고정). Lettuce 기본 지수 백오프는 페일오버 승격(5~6초) 뒤에도 몇 초를
		 * 더 기다리게 한다(2026-11-09 측정: 승격 5.6초, 앱 재연결 9초). 250ms 면 감시자·마스터에 부담 없이 승격 직후 붙는다.
		 */
		private Duration reconnectDelay = Duration.ofMillis(250);
	}

	public enum Mode {
		STANDALONE, SENTINEL
	}
}
