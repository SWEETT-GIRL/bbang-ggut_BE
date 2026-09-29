package sweetgirl.croissant.breadlog.enums;

/**
 * 빵로그 랭킹 판.
 *
 * <p>이번 주 판만 월요일 0시(Asia/Seoul)에 새로 시작하고 나머지는 누적이다.
 * 공유왕만 공개한 빵로그를 세고, 나머지는 비공개까지 센다(개수만 보이고 내용은 안 보인다).
 */
public enum RankingBoard {
	/** 이번 주 빵로그왕 — 이번 주에 쓴 빵로그 수. 내 지역을 고를 수 있다. */
	WEEKLY,
	/** 역대 빵로그왕 — 누적 빵로그 수. 내 지역을 고를 수 있다. */
	RECORDS,
	/** 전국 정복왕 — 빵로그를 남긴 시·군·구 수. */
	REGIONS,
	/** 공유왕 — 공개한 빵로그 수. */
	SHARE;

	/** 전국 / 내 지역을 고를 수 있는 판 — 빵로그 수를 세는 두 판만. */
	public boolean supportsLocal() {
		return this == WEEKLY || this == RECORDS;
	}
}
