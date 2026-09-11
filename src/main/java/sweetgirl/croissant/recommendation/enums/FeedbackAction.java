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
	DISMISS("담지 않고 닫았다", false);

	private final String description;

	/** 가게 단위 반응이면 storeId 가 있어야 한다. */
	private final boolean storeScoped;
}
