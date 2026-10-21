package sweetgirl.croissant.store.service.trending;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import sweetgirl.croissant.store.enums.TrendingSignal;

/**
 * 급부상 Redis 키 규칙. 시각은 서버 시간대(Asia/Seoul) 기준이다.
 *
 * <pre>
 * trend:h:{yyyyMMddHH}                     Sorted Set  가게id → 그 시간 신호 가중합     (8일)
 * trend:uv:{가게}:{yyyyMMdd}                HyperLogLog 그날 신호를 남긴 회원           (8일)
 * seen:{신호}:{가게}:{회원}:{yyyyMMdd}       String      같은 회원·행동은 하루 한 번만    (26시간)
 * trend:top                                Sorted Set  가게id → 급부상 점수            (5분마다 교체)
 * trend:names                              Hash        가게id → 이름·매칭 키 JSON       (5분마다 교체)
 * </pre>
 */
final class TrendingKeys {

	static final String TOP = "trend:top";
	static final String NAMES = "trend:names";
	static final String REFRESH_LOCK = "lock:trend:refresh";

	private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

	private TrendingKeys() {
		// 정적 유틸리티
	}

	static String hourBucket(LocalDateTime time) {
		return "trend:h:" + HOUR.format(time);
	}

	static String uniqueMembers(long storeId, LocalDate day) {
		return "trend:uv:" + storeId + ":" + DAY.format(day);
	}

	static String seen(TrendingSignal signal, long storeId, long memberId, LocalDate day) {
		return "seen:" + signal.name() + ":" + storeId + ":" + memberId + ":" + DAY.format(day);
	}

	/** 교체용 임시 키 — 다 쓴 뒤 RENAME 해서 읽는 쪽이 반쯤 찬 순위표를 보지 않게 한다 */
	static String staging(String key) {
		return key + ":next";
	}
}
