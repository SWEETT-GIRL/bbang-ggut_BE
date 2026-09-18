package sweetgirl.croissant.personalization.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 사용자 × 가게 신호의 종류와 기본 가중치 (설계 §4.2 · §4.4). 가중치를 행에 저장하지 않는 이유는 종류와 1:1 이고 시점 보정(반감기 ·
 * 선택 성향)은 계산 때 곱하기 때문이다 — 값을 바꾸면 다음 배치부터 전 회원에 그대로 적용된다.
 *
 * <p>STORE_VIEW 의 가중치가 0 인 이유: 조회는 전환 여부로 부호가 갈린다(조회 뒤 같은 자리에서 저장 · 담기 +2, 7일 안 없음 −0.3,
 * 두 번 보고 끝내 안 담음 −0.5). 그 판정은 프로필 배치가 SAVE · COURSE_ADD 행과 converted 로 한다(#663).
 */
@Getter
@RequiredArgsConstructor
public enum InteractionType {
	RECORD_BREAD(2.0, "빵 기록의 빵 — menu_id 로 종류 → 축"),
	RECORD_STORE(2.0, "빵 기록의 가게"),
	SAVE(1.0, "가게 저장"),
	PLAN(1.5, "가고 싶은 가게"),
	COURSE_ADD(1.5, "코스에 담기 — 담는 요청의 회원(이벤트), 과거분은 코스 주인(백필)"),
	STORE_VIEW(0.0, "가게 상세 조회 — 하루 한 번, 부호는 전환으로"),
	UNSAVE(-1.0, "저장 해제 — saved_stores 는 하드 삭제라 이벤트로만 남는다"),
	COURSE_REMOVE(-1.0, "담았다가 뺌"),
	RECO_DISMISSED(-0.3, "추천 시트를 담지 않고 닫음 — 보인 가게마다, 묶음당 한 번"),
	RECO_UNCHECK(-1.0, "추천 시트에서 체크 해제");

	private final double weight;
	private final String description;

	public boolean isNegative() {
		return weight < 0;
	}
}
