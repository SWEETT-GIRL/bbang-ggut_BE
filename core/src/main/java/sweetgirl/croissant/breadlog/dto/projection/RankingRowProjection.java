package sweetgirl.croissant.breadlog.dto.projection;

/** 랭킹 한 줄 — 순위 쿼리의 결과. */
public interface RankingRowProjection {

	Long getMemberId();

	String getNickname();

	/** 대표 캐릭터. 세계관 행이 아직 없는 회원은 null. */
	String getSelectedCharacter();

	Long getScore();

	/** 공동 순위 없이 1, 2, 3… */
	Long getRank();

	/** 같은 점수 무리의 맨 앞 순위 — "N개 더 하면 몇 위" 계산에 쓴다. */
	Long getScoreFirstRank();
}
