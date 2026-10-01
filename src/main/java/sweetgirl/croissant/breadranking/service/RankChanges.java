package sweetgirl.croissant.breadranking.service;

import java.util.Map;

/**
 * 한 판의 "어제 대비 변동". 어제 스냅샷이 없거나(첫날·배치 실패) 주간 판 월요일이면 {@link #unknown()} — 변동도 NEW 도 null 이다.
 */
public record RankChanges(Map<Long, Integer> previousRanks, boolean known) {

	public static RankChanges unknown() {
		return new RankChanges(Map.of(), false);
	}

	public static RankChanges of(Map<Long, Integer> previousRanks) {
		return new RankChanges(Map.copyOf(previousRanks), true);
	}

	/** 어제 등수 − 오늘 등수. 양수 ▲, 음수 ▼, 0 같음. 어제 없었으면(NEW) null. 앱은 0 이면 아무것도 안 그린다. */
	public Integer changeOf(long subjectId, long currentRank) {
		if (!known) {
			return null;
		}
		Integer previous = previousRanks.get(subjectId);
		return previous == null ? null : (int)(previous - currentRank);
	}

	/** 어제 상위 100 에 없던 대상. 모르면 null. */
	public Boolean isNew(long subjectId) {
		if (!known) {
			return null;
		}
		return !previousRanks.containsKey(subjectId);
	}
}
