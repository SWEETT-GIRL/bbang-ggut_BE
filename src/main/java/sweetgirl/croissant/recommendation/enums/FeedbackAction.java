package sweetgirl.croissant.recommendation.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 추천 결과에 대한 사용자 반응. 음의 신호(설계 §3.1 "추천 노출 후 미담기 / 체크 해제")의 출처다. */
@Getter
@RequiredArgsConstructor
public enum FeedbackAction {

	VIEW("결과 시트를 봤다", false),
	STORE_TAP("시트 안 가게 행을 탭했다", true),
	STORE_UNCHECK("가게 체크를 해제했다", true),
	SAVE("담았다", false),
	DISMISS("담지 않고 닫았다", false),
	LIKE("결과 시트의 👍 — 추천이 마음에 든다", false),
	DISLIKE("결과 시트의 👎 — 이유 칩(FeedbackReason)을 붙일 수 있다", false),
	RETRY("다른 가게로 다시 받았다 — 서버가 다시 추천을 처리하며 이전 추천에 남긴다(#661)", false);

	private final String description;

	/** 가게 단위 반응이면 storeId 가 있어야 한다. 아니어도 storeId 가 오면 남긴다(앵커 카드의 X · 담기). */
	private final boolean storeScoped;

	/** 이유 칩은 👎 에만 붙는다. */
	public boolean acceptsReason() {
		return this == DISLIKE;
	}

	/** 서버가 기록하는 값 — 피드백 API 로 오면 거부한다. DISMISS 와 섞이면 지표가 흐려진다. */
	public boolean isServerOnly() {
		return this == RETRY;
	}
}
