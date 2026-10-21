package sweetgirl.croissant.searchmiss.service;

import java.util.List;

/**
 * 블로그 글을 근거로 검색어가 무엇을 가리키는지 해석하고, 그 해석을 코드로 검증한 결과.
 *
 * @param officialName {@link Kind#STORE}일 때만 값이 있다. 근거 글에 글자 그대로 들어 있는 것이 확인된 이름이다
 * @param evidenceLinks 이름이 실제로 들어 있던 근거 글 링크
 * @param reason 사람이 읽을 판정 사유
 */
public record KeywordInterpretation(
		Kind kind,
		String officialName,
		List<String> evidenceLinks,
		String reason) {

	public enum Kind {
		/** 검증까지 통과한 가게 이름 */
		STORE,
		/** 메뉴·지역 */
		NOT_A_STORE,
		/** 블로그 글이 하나도 없다 — 네이버 장소도 없고 한글도 없으면 랜덤 문자열로 본다 */
		NO_POSTS,
		/** 근거가 모자라거나 검증 실패 */
		UNRESOLVED
	}

	public static KeywordInterpretation store(String officialName, List<String> evidenceLinks) {
		return new KeywordInterpretation(Kind.STORE, officialName, evidenceLinks, "블로그 근거로 확인");
	}

	public static KeywordInterpretation notAStore(String reason) {
		return new KeywordInterpretation(Kind.NOT_A_STORE, null, List.of(), reason);
	}

	public static KeywordInterpretation noPosts() {
		return new KeywordInterpretation(Kind.NO_POSTS, null, List.of(), "블로그 글 없음");
	}

	public static KeywordInterpretation unresolved(String reason) {
		return new KeywordInterpretation(Kind.UNRESOLVED, null, List.of(), reason);
	}
}
