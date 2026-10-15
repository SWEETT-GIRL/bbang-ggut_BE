package sweetgirl.croissant.breadlog.dto.response;

/**
 * 요즘 많이 기록된 빵 한 줄.
 *
 * @param name  빵 이름(태그 분류 — 소금빵 · 크루아상 …). 분류를 못 정한 '빵' · '기타'는 안 나온다
 * @param count 최근 30 일 안에 그 빵이 태그로 붙은 횟수
 */
public record PopularBreadResponse(String name, long count) {
}
