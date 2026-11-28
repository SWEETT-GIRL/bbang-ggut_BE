package sweetgirl.dataops.reconcile.policy;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 메뉴 제외 필터 — 서비스는 베이커리·디저트만 등록한다 (병합 정책 5장). 음료 + 비디저트(식사·비식품) 제외.
 *
 * 핵심 원리 (2026-08-12 재설계): 메뉴명 분류의 신호는 "맛"이 아니라 **마지막에 등장하는 제품 타입 단어**다.
 *  - "초콜릿라떼" → 끝 제품어가 '라떼'(음료) → 제외   ("초콜릿"은 맛)
 *  - "초콜릿 쿠키" → 끝 제품어가 '쿠키'(디저트) → 통과
 *  - "커피번"     → 끝 제품어가 '번'(빵) → 통과       ("커피"는 맛)
 *
 * 2026-08-13 확장 — menuadd 표본 40곳 실검수 누수 반영 (matching/menuadd_sample_judgments.jsonl):
 *  - 어휘 보강: 티·차·전통음료(수정과·식혜·뱅쇼)·원두 상품(드립·브루잉·산지명)·캔음료, 식사류(파스타)·비식품(포장박스) 신설
 *  - 위치 비교를 매치 **끝 위치** 기준으로 변경 — 짧은 단어가 긴 단어와 같은 위치에서 이기는 오류 방지
 *  - **끝단어 전용 토큰** 신설 — 짧은 토큰(티·차·우유 등)은 이름 끝에서만 판정해 오폭 방지
 *    (티→티라미수·티그레, 우유→인절미우유빙수, 밀크→밀크롤케이크, 말차→말차클래식).
 *    수량·단위 괄호는 층별로 벗기며 검사: "쑥 우유(300ml)" → "쑥 우유" → 제외, "(슬림캔)" 은 괄호 안도 검사
 *
 * 판정:
 *  1. 배민 그룹명 — 실측상 COFFEE·에이드·TEA 등 명시적으로 갈려 가장 정확 → 있으면 그룹으로 판정
 *  2. 메뉴명의 마지막 제외어(음료·식사·비식품) vs 디저트어 — 끝 위치가 뒤인 쪽이 제품 타입
 *  3. 끝단어 전용 토큰 — 단 디저트어로 끝나면 통과가 우선 (비스코티·우유식빵 보호)
 *
 * 알려진 한계: 시그니처 음료명(포레스트아일랜드)·비식품 소품(곰돌이초)은 어휘로 못 잡는다 — 표본 검수로 커버.
 */
@Component
public class BeverageFilter {

	/** 음료 제품 타입어 (맛이 아니라 무엇인지) — 위치 무관 */
	private static final List<String> BEVERAGE_TYPE = List.of(
		"라떼", "라뗴", "아메리카노", "카푸치노", "에스프레소", "에스프레쏘", "콜드브루", "플랫화이트",
		"스무디", "에이드", "주스", "쥬스", "소다", "쉐이크", "셰이크", "프라페", "슈페너", "모히또",
		"샹그리아", "밀크티", "아이스티", "티라떼", "코코아", "핫초코", "카페모카", "커피",
		// 2026-08-13 표본 검수 누수 보강
		"수정과", "식혜", "뱅쇼", "하이볼", "레모네이드", "스파클링", "마끼야또", "마키아토", "마끼아또",
		"드립", "브루잉", "배치브루", "원두", "스페셜티", "디카페인", "쿨피스", "티바나", "착즙", "브렉퍼스트",
		// 원두 산지명 — 원두·드립 단품 메뉴가 산지명만으로 등록된다 ("에티오피아 — 4,000원")
		"에티오피아", "콜롬비아", "과테말라", "온두라스", "케냐", "브라질", "코스타리카", "파나마",
		"예가체프", "게이샤",
		// 2026-08-13 2차 — 전수(94,350) 스캔 누수: 표기 변형·전통음료·탄산·주류
		"라테", "아샷추", "미숫가루", "탄산음료", "탄산수", "사이다", "드링크", "티백", "콤부차",
		"버블티", "히비스커스", "쌍화탕",
		"와인", "맥주", "막걸리", "샴페인", "칵테일", "소주");

	/** 식사·비식품 타입어 — 음료와 동일하게 끝 위치 비교로 제외 (2026-08-13 신설) */
	private static final List<String> NON_DESSERT_TYPE = List.of(
		"파스타", "스파게티", "리조또", "리소토", "떡볶이", "밀키트", "포장박스", "문의", "상차림",
		// 2026-08-13 2차 — 청류(잼·시럽)·소품·클래스 상품
		"수제청", "과일청", "생강청", "레몬청", "유자청", "자몽청",
		"캔들", "비누", "클래스", "원데이",
		// 2026-09-19 식사 확장 (dataops #11) — 5~7월 초기 적재분에 남아 있던 식사 메뉴(운영 2만 7천여 건). 끝 위치 비교라
		// 피자빵·치킨 샌드위치·에그 샐러드 크루아상처럼 빵·디저트어가 더 뒤에서 끝나면 통과한다.
		// 넣지 않은 것: 도시락(도시락 케이크·약식 도시락), 카레(빨미카레), 라면(라면땅) — 운영 표본에서 빵집 상품을 잡았다
		"샐러드", "피자", "볶음밥", "덮밥", "비빔밥", "김밥", "주먹밥", "오므라이스", "필라프", "포케",
		"치킨", "버거", "스테이크", "감자튀김", "만두", "돈까스", "돈가스", "국밥", "우동",
		"까르보나라", "감바스", "그라탕", "그라탱", "나쵸", "타코야끼", "브런치");

	/**
	 * 끝단어 전용 식사 토큰 — 이름 "끝"에서만 판정 (2026-09-19). 위치 무관으로 매칭하면 스프링클·스프레드가 걸린다.
	 */
	private static final List<String> NON_DESSERT_SUFFIX_ONLY = List.of("스프", "수프");

	/** 디저트·빵 제품 타입어 */
	private static final List<String> DESSERT_TYPE = List.of(
		"버터바",
		"빵", "번", "케이크", "케잌", "케익", "쿠키", "마카롱", "마들렌", "휘낭시에", "휘낭", "스콘", "파이",
		"타르트", "크로플", "크로와상", "크루아상", "베이글", "도넛", "도너츠", "브라우니", "갸또",
		"쇼콜라", "사브레", "다쿠아즈", "롤케익", "롤케이크", "티라미수", "푸딩", "젤라또", "빙수",
		"바게트", "치아바타", "앙버터", "몽블랑", "에클레어", "카스테라", "카스텔라", "와플", "찐빵",
		"호떡", "붕어빵", "꽈배기", "약과", "양갱", "떡", "젤리", "초콜릿", "초콜렛", "초코릿",
		"구겔호프", "쌀과자", "치즈볼", "구움과자", "파운드", "비스킷", "비스코티", "토스트", "샌드위치",
		"베이크", "슈크림", "생크림", "카롱",
		// 2026-08-13 보강 — 표본 확인 디저트 타입 + 끝단어 토큰 오폭 보호(비스코티·세레니티·티그레)
		"밀푀유", "갈레뜨", "까눌레", "크루키", "프레즐", "산도", "캔디", "티그레", "아이스크림",
		"아포가토", "고로케", "핫도그", "파니니", "토스티", "크로크무슈", "세레니티",
		// 2026-08-13 2차 — 전수 스캔 오폭 보호: 샹티(크림)·설기(떡)·아포카토(표기)·요거트볼
		"샹티", "설기", "인절미", "아포카토", "모찌", "요거트볼",
		// 2026-09-19 — 끝단어 식사 토큰 '스프' 오폭 보호(크리스프 과자)
		"크리스프");

	/**
	 * 끝단어 전용 음료 토큰 — 이름 "끝"에서만 판정. 위치 무관으로 매칭하면 오폭이 크다
	 * (얼그레이→"얼그레이 밀푀유", 말차→"말차클래식", 콜라→"뺑오쇼콜라"는 디저트).
	 */
	private static final List<String> BEVERAGE_SUFFIX_ONLY = List.of(
		"티", "차", "말차", "우유", "밀크", "캔", "칡", "tea",
		"얼그레이", "페퍼민트", "캐모마일", "루이보스",
		// 2026-08-13 2차
		"콜라", "음료", "요구르트", "우롱", "라씨", "에일", "그린티");

	private static final Set<String> GROUP_EXACT = Set.of("티", "차", "ade", "tea");
	private static final List<String> GROUP_CONTAINS = List.of(
		"커피", "coffee", "음료", "beverage", "드링크", "drink", "에이드", "스무디", "smoothie",
		"라떼", "latte", "주스", "juice", "쥬스", "프라페", "쉐이크", "shake", "논커피", "non coffee");

	/** 이름 끝 검사 전 제거할 꼬리 문자 (닫는 괄호는 괄호 층 처리를 위해 남긴다) */
	private static final String TRAILING_NOISE = " \t.,-_~!/&+*·]";
	/** A flavor after an explicit product is not a second product (버터바 말차, 쿠키 얼그레이). */
	private static final java.util.regex.Pattern DESSERT_FLAVOR_SUFFIX = java.util.regex.Pattern.compile(
		"\\s*(?:[-:/]\\s*)?(말차|녹차|얼그레이|초코|초콜릿|바닐라|딸기|레몬|흑임자)(?:맛)?\\s*");
	private static final java.util.regex.Pattern POKE = java.util.regex.Pattern.compile(
		"포케|(?<![a-z])poke(?![a-z])", java.util.regex.Pattern.CASE_INSENSITIVE);

	/** Explicit service policy: poke is a meal, including names that also contain 베이글. */
	public boolean isPoke(String menuName) {
		return menuName != null && POKE.matcher(menuName).find();
	}

	/**
	 * 용량 표기(350ml·1리터·1L)·테이크아웃 표기 — 병입 음료/보틀 신호 (2026-08-14 실측 354+14건 전량 음료).
	 * 위치 무관 매치 시 즉시 제외 — 끝단어 비교보다 우선한다 (사용자 정책 확정).
	 */
	private static final java.util.regex.Pattern VOLUME_OR_TAKEOUT = java.util.regex.Pattern.compile(
		"\\d+(\\.\\d+)?\\s*(ml|리터|l\\b)|take\\s*-?\\s*out|테이크\\s*아웃");

	public boolean isExcluded(String menuName, String groupName) {
		if (isPoke(menuName)) return true;
		// 1. 배민 그룹명 — 가장 정확
		if (groupName != null) {
			String group = groupName.toLowerCase(Locale.ROOT).strip();
			if (GROUP_EXACT.contains(group) || GROUP_CONTAINS.stream().anyMatch(group::contains)) {
				return true;
			}
		}
		if (menuName == null) {
			return false;
		}
		String name = menuName.toLowerCase(Locale.ROOT);

		if (VOLUME_OR_TAKEOUT.matcher(name).find()) {
			return true;
		}

		// 2. 마지막 제외어 vs 디저트어 — 매치 "끝" 위치 비교 (같은 시작이면 긴 단어가 이긴다).
		// 괄호 안 수식("(최소주문단위 10개)"의 '소주')이 위치를 오염시키므로 괄호 그룹은 제거 후 비교
		String flat = stripParenGroups(name);
		int lastExcluded = Math.max(lastEndOfAny(flat, BEVERAGE_TYPE), lastEndOfAny(flat, NON_DESSERT_TYPE));
		int lastDessert = lastEndOfAny(flat, DESSERT_TYPE);
		if (lastDessert > lastExcluded && lastDessert > 0
			&& DESSERT_FLAVOR_SUFFIX.matcher(flat.substring(lastDessert).strip()).matches()) {
			return false;
		}
		if (lastExcluded >= 0 && lastExcluded >= lastDessert) {
			return true;
		}

		// 3. 끝단어 전용 토큰 — 수량·단위 괄호를 층별로 벗기며 이름 끝을 검사
		String remaining = name.strip();
		while (!remaining.isEmpty()) {
			String tail = trimTrailingNoise(remaining);
			if (endsWithAny(tail, DESSERT_TYPE)) {
				return false; // 디저트어로 끝나면 통과 우선 (비스코티·우유식빵)
			}
			if (endsWithAny(tail, BEVERAGE_SUFFIX_ONLY) || endsWithAny(tail, NON_DESSERT_SUFFIX_ONLY)) {
				return true;
			}
			if (tail.endsWith(")")) {
				int open = tail.lastIndexOf('(');
				if (open < 0) {
					break;
				}
				String inner = tail.substring(open + 1, tail.length() - 1).strip();
				// "(슬림캔)" — 괄호 안 단위가 음료 신호일 수 있다
				if (!endsWithAny(inner, DESSERT_TYPE) && endsWithAny(inner, BEVERAGE_SUFFIX_ONLY)) {
					return true;
				}
				remaining = tail.substring(0, open);
				continue;
			}
			break;
		}
		return false;
	}

	/** 후보 단어들 중 문자열에서 가장 뒤에 "끝나는" 위치 (index + length, 없으면 -1) */
	private int lastEndOfAny(String text, List<String> words) {
		int last = -1;
		for (String word : words) {
			int index = text.lastIndexOf(word);
			if (index >= 0 && index + word.length() > last) {
				last = index + word.length();
			}
		}
		return last;
	}

	private boolean endsWithAny(String text, List<String> words) {
		for (String word : words) {
			if (text.endsWith(word)) {
				return true;
			}
		}
		return false;
	}

	private String trimTrailingNoise(String text) {
		int end = text.length();
		while (end > 0 && TRAILING_NOISE.indexOf(text.charAt(end - 1)) >= 0) {
			end--;
		}
		return text.substring(0, end);
	}

	/** 괄호 그룹을 안쪽부터 반복 제거 — 중첩("(M (ICE))")도 처리. 짝 안 맞는 괄호는 남는다 */
	private String stripParenGroups(String text) {
		String current = text;
		while (current.indexOf('(') >= 0) {
			String next = current.replaceAll("\\([^()]*\\)", " ");
			if (next.equals(current)) {
				break;
			}
			current = next;
		}
		return current;
	}
}
