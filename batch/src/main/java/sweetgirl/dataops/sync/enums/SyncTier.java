package sweetgirl.dataops.sync.enums;

import java.time.Duration;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SyncTier {

	/** 구독 보유 + 정제 μ 조회 상위 — 주 2회 */
	T0(Duration.ofHours(84)),
	/** 나머지 네이버 매칭 가게 전체 (메뉴 0개 포함) — 주 1회 */
	T1(Duration.ofDays(7));

	private final Duration period;
}
