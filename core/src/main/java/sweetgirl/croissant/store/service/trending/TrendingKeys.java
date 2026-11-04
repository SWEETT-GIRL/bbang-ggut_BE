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
 * trend:ranking                            String      급부상 순위표 JSON(점수 높은 순)   (주기마다 교체, 주기×3)
 * </pre>
 */
final class TrendingKeys {

	static final String RANKING = "trend:ranking";
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
}
