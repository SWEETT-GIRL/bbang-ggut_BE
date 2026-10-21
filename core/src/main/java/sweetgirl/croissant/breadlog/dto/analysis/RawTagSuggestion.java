package sweetgirl.croissant.breadlog.dto.analysis;

/**
 * 접기 전의 제안 하나. 모델은 같은 빵을 여러 줄로 답하기도 하고 목록 밖 menuId 를 내기도 한다.
 *
 * @param boxArea 상자 넓이(0~1 좌표계). 같은 메뉴가 겹쳐 오면 이 값이 큰 것을 남긴다 — 제일 잘 보이는 것
 */
public record RawTagSuggestion(Long menuId, double x, double y, double boxArea) {
}
