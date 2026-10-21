package sweetgirl.croissant.breadlog.dto.analysis;

/** 프롬프트에 후보로 넣는 가게 메뉴. 모델이 고를 수 있는 답은 이 목록뿐이다 */
public record StoreMenuOption(Long id, String name) {
}
