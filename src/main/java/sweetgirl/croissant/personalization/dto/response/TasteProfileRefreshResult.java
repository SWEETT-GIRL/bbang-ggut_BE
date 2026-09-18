package sweetgirl.croissant.personalization.dto.response;

import sweetgirl.croissant.personalization.enums.TasteProfileRefreshOutcome;

/**
 * 프로필 재계산 한 번의 결과. recomputed 만 version 이 올랐다(추천 재사용 키가 바뀐 회원 수). failed 는 회원별 트랜잭션이 실패해
 * 건너뛴 수 — 로그에 회원 id 가 남고 다음 배치에서 다시 시도한다.
 */
public record TasteProfileRefreshResult(int members, int recomputed, int statsOnly, int unchanged, int failed) {

	public static TasteProfileRefreshResult single(TasteProfileRefreshOutcome outcome) {
		return new TasteProfileRefreshResult(1, outcome == TasteProfileRefreshOutcome.RECOMPUTED ? 1 : 0,
			outcome == TasteProfileRefreshOutcome.STATS_ONLY ? 1 : 0,
			outcome == TasteProfileRefreshOutcome.UNCHANGED ? 1 : 0, 0);
	}
}
