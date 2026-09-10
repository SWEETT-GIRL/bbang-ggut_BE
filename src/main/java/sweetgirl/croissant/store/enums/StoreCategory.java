package sweetgirl.croissant.store.enums;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 가게 분류. DB(store.category)에는 이 enum 의 이름(BAKERY, CAFE_DESSERT ...)만 들어간다.
 *
 * <p>각 상수가 들고 있는 문자열은 네이버·카카오가 주는 원문 카테고리다. 외부 표기가 바뀌면
 * 이 목록만 고치면 되고 이미 저장된 store.category 값은 건드리지 않는다. 반대로 enum 이름을
 * 바꾸면 저장된 데이터를 옮겨야 하므로 이름은 바꾸지 않는다 — 그게 원문을 그대로 넣지 않는 이유다.
 *
 * <p>{@link #OTHER} 는 "외부 값은 받았는데 우리 분류에 없다"는 뜻이고, 컬럼이 null 인 것은
 * "외부 카테고리를 아직 못 받았다"는 뜻이라 서로 다르다.
 *
 * <p>분류 기준은 네이버 카테고리 247종 46,578곳 실측(2026-09-10)이다. 아래 21종이 98.9% 를 덮고,
 * OTHER 로 남는 1.1%(513곳)는 방면정보·기업·꽃집·전기차충전소처럼 빵집이 아닌 것들이다.
 */
public enum StoreCategory {

	// ── 빵·디저트
	BAKERY("베이커리", "빵류제조", "제과,제빵", "제과,베이커리", "과자류제조", "제과점", "베이커리카페", "경주빵", "커피번"),
	CAFE_DESSERT("카페,디저트", "카페", "디저트카페", "디저트", "디저트 팝업", "테이크아웃커피", "커피전문점",
			"플라워카페", "갤러리카페", "북카페", "테마카페", "루프탑카페"),
	CAKE("케이크전문", "케이크", "떡케이크"),
	DONUT("도넛"),
	BAGEL("베이글"),
	SANDWICH("샌드위치", "토스트", "핫도그", "햄버거"),
	BRUNCH("브런치카페", "브런치"),
	WAFFLE_CREPE("와플", "크레페", "붕어빵", "타코야끼"),
	CHOCOLATE("초콜릿전문점", "과자,사탕,초코렛", "마카롱", "타르트", "푸딩"),

	// ── 전통 간식
	RICE_CAKE("떡,한과", "떡류제조", "떡카페", "한과", "떡"),
	WALNUT_CAKE("호두과자"),
	HOTTEOK("호떡"),
	STEAMED_BUN("찐빵", "만두", "왕만두", "칼국수,만두"),

	// ── 빵집이 아닌 업종
	ICE_CREAM("아이스크림", "빙수", "젤라또", "젤라토", "요거트아이스크림"),
	BEVERAGE("차", "차,커피", "전통찻집", "홍차전문점", "과일,주스전문점", "생과일주스", "스무디", "다방"),
	RESTAURANT("양식", "한식", "중식", "일식", "일식당", "중식당", "종합분식", "분식", "떡볶이",
			"이탈리아음식", "스파게티,파스타전문", "프랑스음식", "피자", "치킨", "돈까스", "국수",
			"다이어트,샐러드", "도시락,컵밥"),
	PUB("바(BAR)", "맥주,호프", "와인", "와인바", "요리주점", "칵테일바", "이자카야"),
	GROCERY("가공식품", "식료품", "식료품제조", "유기농산물마트", "슈퍼,마트", "편의점", "종합도소매", "반찬가게"),
	WORKSHOP("공방"),
	EDUCATION("요리교육", "취미교육", "놀이교육", "스포츠,레크레이션교육", "학원 등 교습시설",
			"체험,홍보관", "체험마을", "관람,체험"),

	/** 외부 카테고리는 받았지만 위 어디에도 없는 값. */
	OTHER();

	private static final Map<String, StoreCategory> BY_EXTERNAL_NAME = buildIndex();

	private final Set<String> externalNames;

	StoreCategory(String... externalNames) {
		this.externalNames = Set.of(externalNames);
	}

	/**
	 * 네이버·카카오 원문 카테고리를 우리 분류로 옮긴다.
	 *
	 * <p>카카오는 "음식점 &gt; 양식 &gt; 베이커리" 처럼 경로로 주므로 잎에서 뿌리 방향으로 훑는다 —
	 * 잎이 브랜드명이면 상위 마디가 잡힌다. 네이버는 "떡,한과" 처럼 쉼표가 이름의 일부라 쉼표로는 쪼개지 않는다.
	 *
	 * @return 값이 비어 있으면 null(미수집), 매핑이 없으면 {@link #OTHER}
	 */
	public static StoreCategory fromExternalName(String rawCategoryName) {
		if (rawCategoryName == null || rawCategoryName.isBlank()) {
			return null;
		}
		String[] nodes = rawCategoryName.split(">");
		for (int i = nodes.length - 1; i >= 0; i--) {
			StoreCategory matched = BY_EXTERNAL_NAME.get(nodes[i].trim());
			if (matched != null) {
				return matched;
			}
		}
		return OTHER;
	}

	public Set<String> externalNames() {
		return externalNames;
	}

	private static Map<String, StoreCategory> buildIndex() {
		Map<String, StoreCategory> index = new HashMap<>();
		for (StoreCategory category : values()) {
			for (String externalName : category.externalNames) {
				StoreCategory previous = index.put(externalName, category);
				if (previous != null) {
					throw new IllegalStateException(
						"외부 카테고리 '" + externalName + "' 가 " + previous + " 와 " + category + " 에 겹쳐 있다");
				}
			}
		}
		return Collections.unmodifiableMap(index);
	}
}
