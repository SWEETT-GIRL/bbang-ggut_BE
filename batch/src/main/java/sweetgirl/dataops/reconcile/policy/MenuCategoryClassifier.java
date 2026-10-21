package sweetgirl.dataops.reconcile.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 메뉴명 → 세분류(MenuKind) 분류 — BeverageFilter 와 같은 "마지막 제품 타입 단어" 원리.
 * 본 앱에서 menu_category.name 이 곧 MenuKind 문자열이다 (TrendQueryService 가 mc.name AS menuKind 로 사용,
 * 대분류 MenuCategory enum 은 kind 집합에서 코드로 파생). 매핑은 본 앱 MenuKind enum 68종 전체 기준.
 *
 * 반환은 kind 이름 문자열 — ServingWriter 가 INSERT 시 이름→id 서브쿼리로 해석한다.
 * 매핑 불가는 null — menu_category_id 를 NULL 로 넣고, 본 앱 BE 의 메뉴 AI 분류 스케줄러가 주워
 * Gemini 로 분류한다 (2026-09-08. 그전엔 '기타' 폴백이었고 '빵' 그물도 있어 27만 건이 그 둘에 갇혔다).
 * menu_category 는 2026-09-07 기준 89행으로 enum 과 정합.
 *
 * 끝단어 판정을 믿기 어려우면(doubt — 키슈→슈 · 떡갈비→떡 · 닭강정→한과 · 인절미빵→떡 …) 역시 null 이다 — 틀린 칸에 넣느니
 * 미분류 큐로 보내 AI 가 분류하게 한다 (2026-09-19, dataops #11). 규칙은 scripts/menu_classifier.py 의 doubt() 와 같다 —
 * 한쪽을 고치면 다른 쪽도. 파이썬은 이 파일의 mapping.put(...) 줄을 정규식으로 읽으니 그 줄 모양은 바꾸지 않는다.
 */
@Component
public class MenuCategoryClassifier {

	/** 운영 menu_category.name → 끝단어 변형들. 매칭은 끝 위치 우선, 동률이면 긴 단어 우선 */
	private static final Map<String, List<String>> CATEGORY_END_WORDS = buildMapping();

	private static final Pattern PAREN = Pattern.compile("\\([^()]*\\)");

	// 끝단어 판정을 믿기 어려운 경우 — 2차 재분류 plan 표본(2026-09-19)에서 나온 것. menu_classifier.py 와 같은 식이다.
	// UNICODE_CHARACTER_CLASS: 파이썬 re 처럼 \s 가 NBSP·전각 공백을, \d 가 전각 숫자를 잡게 한다.
	private static final Pattern HOT_ICE_CHOCOLATE = Pattern.compile("(핫|아이스|hot|ice)\\s*초(코|콜)",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS); // 핫초콜릿·아이스 초콜렛 — 음료
	private static final Pattern MEAL_OMELETTE = Pattern.compile(
		"브런치|플레이트|플래터|베이컨|그레이비|라이스|밥|샐러드"); // 달걀 요리 오믈렛 (빵집 오믈렛은 딸기오믈렛 같은 과자)
	private static final Pattern QUANTITY_TAIL = Pattern.compile(
		"[\\s\\d]*(개입|개|입|구|pcs|ea|p|조각|마리|알|봉|세트|set)?\\s*$",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
	/** 빵 이름 앞에 오면 그 종류가 아니라 속재료·맛이다(인절미빵·슈크림빵·파이앙꼬빵) */
	private static final Set<String> FILLING_KINDS = Set.of("떡", "슈", "파이", "한과", "초콜릿");

	/** 끝단어 판정 — 종류(없으면 null), 종류 단어가 끝나는 위치, 걸린 단어 */
	private record Match(String kind, int end, String word) {
	}

	private static Map<String, List<String>> buildMapping() {
		Map<String, List<String>> mapping = new LinkedHashMap<>();
		mapping.put("소금빵", List.of("소금빵"));
		mapping.put("식빵", List.of("식빵"));
		mapping.put("바게트", List.of("바게트", "바게뜨", "바켓트"));
		mapping.put("크루아상", List.of("크루아상", "크로와상", "크로아상", "크라상", "크루아쌍"));
		mapping.put("퀸아망", List.of("퀸아망", "뀐아망"));
		mapping.put("치아바타", List.of("치아바타", "치아바따"));
		mapping.put("포카치아", List.of("포카치아", "포카챠", "포카시아"));
		mapping.put("사워도우", List.of("사워도우", "샤워도우"));
		mapping.put("깜빠뉴", List.of("깜빠뉴", "캄파뉴", "깜파뉴"));
		mapping.put("베이글", List.of("베이글"));
		mapping.put("샌드위치", List.of("샌드위치", "샌드윗치", "샌드"));
		mapping.put("토스트", List.of("토스트", "토스티", "프렌치토스트"));
		mapping.put("쫀득빵", List.of("쫀득빵"));
		mapping.put("맘모스", List.of("맘모스", "맘모스빵"));
		mapping.put("앙버터", List.of("앙버터"));
		mapping.put("소보로", List.of("소보로", "소보루"));
		mapping.put("브리오슈", List.of("브리오슈", "브리오쉬"));
		mapping.put("페이스트리", List.of("페이스트리", "페스츄리", "패스츄리", "페스트리", "데니쉬", "데니시"));
		mapping.put("스콘", List.of("스콘"));
		mapping.put("에그타르트", List.of("에그타르트", "에그타르뜨", "에그따르트"));
		mapping.put("타르트", List.of("타르트", "따르트", "타르뜨"));
		mapping.put("휘낭시에", List.of("휘낭시에", "피낭시에"));
		mapping.put("마들렌", List.of("마들렌", "마들렌느", "마들랜"));
		mapping.put("까눌레", List.of("까눌레", "카눌레", "꺄눌레"));
		mapping.put("쿠키", List.of("쿠키", "사브레"));
		mapping.put("브라우니", List.of("브라우니"));
		mapping.put("케이크", List.of("케이크", "케익", "케잌", "갸또", "가또", "가토", "시루"));
		mapping.put("푸딩", List.of("푸딩"));
		mapping.put("러스크", List.of("러스크"));
		mapping.put("파이", List.of("파이"));
		mapping.put("도넛", List.of("도넛", "도너츠", "도나스", "도넛츠", "도우넛", "꽈배기"));
		mapping.put("떡", List.of("떡", "설기", "인절미", "찹쌀떡", "가래떡", "경단", "송편"));
		mapping.put("소세지빵", List.of("소세지빵", "소시지빵"));
		mapping.put("마늘빵", List.of("마늘빵", "갈릭브레드"));
		mapping.put("고구마빵", List.of("고구마빵"));
		mapping.put("밤빵", List.of("밤빵"));
		mapping.put("빙수", List.of("빙수", "팥빙수", "설빙"));
		// ---- MenuKind 확장분 (menu_category 테이블 미시딩 — 시딩 전엔 SQL 이 '기타'로 폴백) ----
		mapping.put("티라미수", List.of("티라미수", "티라미슈"));
		mapping.put("크로플", List.of("크로플"));
		mapping.put("아이스크림", List.of("아이스크림", "젤라또", "젤라토", "소프트콘", "아포가토", "아포카토")); // 아포카토 표기도 — 케이크 별칭 "가토"에 잡히던 오분류 (2026-09-04 재분류 드라이런 실측 ~860건)
		mapping.put("브륄레", List.of("브륄레", "브릴레", "크렘브륄레"));
		mapping.put("버터떡", List.of("버터떡"));
		mapping.put("요거트", List.of("요거트", "요거트볼", "그릭요거트"));
		mapping.put("크로크무슈", List.of("크로크무슈", "크로크무시", "크로크마담"));
		mapping.put("파운드", List.of("파운드"));
		mapping.put("쫀득쿠키", List.of("쫀득쿠키", "쫀쿠")); // 쫀쿠 = 약칭 (오레오쫀쿠·두쫀쿠 실측 117건)
		mapping.put("번", List.of("번"));
		mapping.put("롤", List.of("롤", "시나몬롤", "소금롤"));
		mapping.put("버터바", List.of("버터바"));
		mapping.put("에끌레어", List.of("에끌레어", "에클레어", "에끌레르"));
		mapping.put("마카롱", List.of("마카롱", "카롱", "뚱카롱"));
		mapping.put("슈", List.of("슈", "슈크림"));
		mapping.put("프레첼", List.of("프레첼", "프레즐", "프레츨", "프레첼"));
		mapping.put("몽블랑", List.of("몽블랑"));
		mapping.put("고로케", List.of("고로케", "고로께", "고르케", "크로켓"));
		mapping.put("프레지에", List.of("프레지에"));
		mapping.put("비스킷", List.of("비스킷", "비스켓", "비스코티"));
		mapping.put("티그레", List.of("티그레"));
		mapping.put("크림빵", List.of("크림빵"));
		mapping.put("샌드베이글", List.of("샌드베이글", "샌드베이들"));
		mapping.put("떠먹케", List.of("떠먹케"));
		mapping.put("와플", List.of("와플"));
		mapping.put("맘모롤", List.of("맘모롤", "맘모스롤"));
		mapping.put("머핀", List.of("머핀")); // 2026-08-14 kind 추가 (본 앱 PR #603) — 시딩 전엔 SQL 이 기타 폴백
		mapping.put("다쿠아즈", List.of("다쿠아즈", "다크아즈", "다쿠와즈"));
		// 2026-09-07 본 앱 MenuKind 22종 추가 (V20260907171000) — AI 분류 실측에서 칸이 없어 '빵'·'기타'로 떨어지던 품목
		mapping.put("호두과자", List.of("호두과자"));
		mapping.put("카스테라", List.of("카스테라", "카스텔라"));
		mapping.put("단팥빵", List.of("단팥빵", "팥빵", "앙금빵"));
		mapping.put("크레페", List.of("크레페", "크레이프"));
		mapping.put("모닝빵", List.of("모닝빵"));
		mapping.put("츄러스", List.of("츄러스", "추로스"));
		mapping.put("붕어빵", List.of("붕어빵", "잉어빵"));
		mapping.put("계란빵", List.of("계란빵", "달걀빵"));
		mapping.put("산도", List.of("산도"));
		mapping.put("오란다", List.of("오란다"));
		mapping.put("약과", List.of("약과"));
		mapping.put("크룽지", List.of("크룽지"));
		mapping.put("수플레", List.of("수플레", "수풀레"));
		mapping.put("화과자", List.of("화과자"));
		mapping.put("통밀빵", List.of("통밀빵", "호밀빵"));
		mapping.put("땅콩빵", List.of("땅콩빵"));
		mapping.put("오믈렛", List.of("오믈렛"));
		mapping.put("커피콩빵", List.of("커피콩빵"));
		mapping.put("모나카", List.of("모나카"));
		mapping.put("소라빵", List.of("소라빵"));
		mapping.put("꿀빵", List.of("꿀빵"));
		mapping.put("만쥬", List.of("만쥬", "만주"));
		// 2026-09-08 본 앱 MenuKind 2종 추가 (V20260908170000) — 미확정 이름 실측에서 칸이 없어 갈리던 품목.
		// '쇼콜라' 는 넣지 않는다 — 퐁당쇼콜라·쇼콜라퍼지처럼 끝단어가 쇼콜라인 케이크류가 초콜릿으로 잡힌다.
		mapping.put("한과", List.of("한과", "정과", "강정", "전병", "유과"));
		mapping.put("초콜릿", List.of("초콜릿", "초콜렛"));
		// generic '빵' 그물은 2026-09-08 제거 — 구체 kind 가 없으면 null 로 넘겨 본 앱 스케줄러가 AI 로 분류한다.
		// 그물이 있으면 "버섯 크림 리조또" 까지 '빵' 에 갇혀 진짜 빵과 구분할 수 없었다 (운영 실측 15.9만 건).
		return mapping;
	}

	/** @return 운영 menu_category.name — 매핑 불가거나 판정을 믿기 어려우면(doubt) null */
	public String classify(String menuName) {
		Match match = match(menuName);
		if (match.kind() == null || !doubt(menuName, match.kind(), match.word()).isEmpty()) {
			return null;
		}
		return match.kind();
	}

	/** 끝단어 판정을 믿기 어려운 이유 — 없거나 매핑 불가면 "" (테스트·운영 확인용) */
	String doubt(String menuName) {
		Match match = match(menuName);
		return doubt(menuName, match.kind(), match.word());
	}

	/** 끝나는 위치가 가장 뒤인 단어, 같으면 긴 단어(에그타르트 > 타르트). 소문자화하고 괄호 안은 지운다 */
	private Match match(String menuName) {
		if (menuName == null) {
			return new Match(null, -1, null);
		}
		String name = normalize(menuName);
		String best = null;
		int bestEnd = -1;
		String bestWord = null;
		for (Map.Entry<String, List<String>> entry : CATEGORY_END_WORDS.entrySet()) {
			for (String word : entry.getValue()) {
				int index = name.lastIndexOf(word);
				if (index < 0) {
					continue;
				}
				int end = index + word.length();
				// 끝 위치가 더 뒤면 교체, 동률이면 더 긴(구체적인) 단어가 이긴다 — 에그타르트 > 타르트
				if (end > bestEnd || (end == bestEnd && word.length() > bestWord.length())) {
					best = entry.getKey();
					bestEnd = end;
					bestWord = word;
				}
			}
		}
		return new Match(best, bestEnd, bestWord);
	}

	private static String normalize(String menuName) {
		return PAREN.matcher(menuName.toLowerCase(Locale.ROOT)).replaceAll(" ");
	}

	/** 걸린 단어가 다른 말의 일부이기 쉬운가 — 한 글자(슈·번·롤·떡)와 '샌드'. 키슈→슈 · 1번 세트→번 · 캐롤→롤 · 떡갈비→떡 · 버터샌드→샌드위치 */
	private static boolean weakMatch(String word) {
		return word != null && (word.length() == 1 || word.equals("샌드"));
	}

	/** 분류 결과를 믿기 어려운 이유(없으면 "") — menu_classifier.py doubt() 와 같은 규칙·같은 순서 */
	private String doubt(String menuName, String kind, String word) {
		if (kind == null) {
			return "";
		}
		if (weakMatch(word)) {
			return "짧은 단어 '" + word + "'";
		}
		String name = normalize(menuName == null ? "" : menuName);
		if (kind.equals("한과") && name.contains("닭강정")) {
			return "닭강정";
		}
		if (kind.equals("초콜릿") && HOT_ICE_CHOCOLATE.matcher(name).find()) {
			return "초콜릿 음료";
		}
		if (kind.equals("오믈렛") && MEAL_OMELETTE.matcher(name).find()) {
			return "식사 오믈렛";
		}
		// "인절미빵"·"슈크림찐빵"·"백설기빵": 끝말이 ○○빵인데 종류 단어가 그 앞(속재료)에서 끝난다. 09-08 에 '빵' 그물을 없앤 뒤
		// 종류 목록에 없는 빵은 속재료 단어가 이긴다 — 인절미빵이 떡, 슈크림찐빵이 슈가 된다. 띄어쓰기만 지워도 판정이 바뀌는 것
		// ("바닐라 슈크림 빵" 은 슈, "바닐라슈크림빵" 은 크림빵)도 같은 이유로 믿지 않는다.
		// 단, 종류 단어 바로 뒤에 빵이 붙은 것(초코소보로빵·카라멜데니시빵)은 그 종류다 — 속재료 종류(FILLING_KINDS)만 빼고.
		String core = QUANTITY_TAIL.matcher(name).replaceAll("").replace(" ", "");
		if (core.endsWith("빵")) {
			Match coreMatch = match(core);
			boolean kindThenBread = coreMatch.end() == core.length() - 1 && kind.equals(coreMatch.kind())
				&& !FILLING_KINDS.contains(kind);
			if (!kindThenBread && (coreMatch.end() < core.length() || !kind.equals(coreMatch.kind()))) {
				return "끝말이 ○○빵(종류 단어는 속재료)";
			}
		}
		return "";
	}
}
