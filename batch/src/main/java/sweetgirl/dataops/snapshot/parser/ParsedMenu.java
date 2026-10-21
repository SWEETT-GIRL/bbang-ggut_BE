package sweetgirl.dataops.snapshot.parser;

import java.util.List;

/**
 * price 는 원본 그대로의 문자열 — 정규화(숫자 변환·"변동" 처리·"[인기]" 태그 제거)는 판정층 몫이다.
 * groupName 은 배민 메뉴의 소속 그룹명(음료 제외 필터의 입력) — 네이버 일반 메뉴는 null.
 */
public record ParsedMenu(
		String name,
		String price,
		String description,
		List<String> imageUrls,
		String groupName) {
}
