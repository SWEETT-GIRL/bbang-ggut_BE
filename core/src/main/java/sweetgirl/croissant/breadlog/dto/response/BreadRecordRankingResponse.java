package sweetgirl.croissant.breadlog.dto.response;

import java.util.List;

/**
 * 빵로그 랭킹 한 판.
 *
 * @param sido    내 지역 판이면 그 시·도, 전국이면 null
 * @param sigungu 내 지역 판이면 그 시·군·구("동대문구"·"수원시"), 전국이면 null
 * @param entries 1위부터 요청한 수만큼. 공동 순위 없이 1, 2, 3…
 * @param me      보는 사람의 자리. 게스트면 null
 * @param regionMemberCount 내 지역 판이면 그 구로 인증한 사람 수(앱은 3명 미만이면 판 대신 친구 초대를 보인다), 전국이면 null
 */
public record BreadRecordRankingResponse(
		String sido, String sigungu, List<Entry> entries, Me me, Long regionMemberCount) {

	public BreadRecordRankingResponse(String sido, String sigungu, List<Entry> entries, Me me) {
		this(sido, sigungu, entries, me, null);
	}

	public BreadRecordRankingResponse withRegionMemberCount(Long count) {
		return new BreadRecordRankingResponse(sido, sigungu, entries, me, count);
	}

	/**
	 * @param character  대표 캐릭터 코드. 세계관을 아직 시작 안 한 회원은 null — 앱이 마스코트로 보여준다
	 * @param isMe       보는 사람 본인인가
	 * @param rankChange 어제 대비(양수 ▲, 음수 ▼, 0 같음 — 0 이면 앱은 아무것도 안 그린다). 어제 스냅샷이 없거나 NEW 면 null
	 * @param isNew      어제 상위 100 에 없던 사람. 어제 스냅샷이 없으면 null. 주간 판은 월요일에 둘 다 null
	 */
	public record Entry(
			long rank,
			String nickname,
			String character,
			long score,
			boolean isMe,
			Integer rankChange,
			Boolean isNew,
			/** 지난주 전국 주간 1 위라 왕관을 쓰고 있는가 */
			boolean crowned) {

		public Entry(long rank, String nickname, String character, long score, boolean isMe, Integer rankChange,
				Boolean isNew) {
			this(rank, nickname, character, score, isMe, rankChange, isNew, false);
		}
	}

	/**
	 * @param rank  순위. 점수가 0이면(순위 밖) null
	 * @param next  한 칸 위로 가는 목표. 1위이거나 순위 밖이면 null
	 */
	public record Me(Long rank, long score, Next next) {
	}

	/** 바로 위 사람을 확실히 넘으려면 {@code gap} 만큼 더 — 그러면 {@code rank} 위. */
	public record Next(long gap, long rank) {
	}
}
