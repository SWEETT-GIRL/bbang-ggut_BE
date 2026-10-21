package sweetgirl.croissant.breadlog.dto.projection;

/** 한 주의 빵순이 점수 한 줄 — 회원 id 와 사진 있는 기록 수. */
public interface WeeklyScoreProjection {
	Long getMemberId();

	Long getScore();
}
