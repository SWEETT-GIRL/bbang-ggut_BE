package sweetgirl.dataops.reconcile.policy;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MenuCategoryClassifierTest {

	private final MenuCategoryClassifier classifier = new MenuCategoryClassifier();

	@Test
	void 끝_제품어로_카테고리를_해석한다() {
		assertThat(classifier.classify("소금빵")).isEqualTo("소금빵");
		assertThat(classifier.classify("티라미수 크로와상")).isEqualTo("크루아상");
		assertThat(classifier.classify("우유식빵")).isEqualTo("식빵");
		assertThat(classifier.classify("얼그레이스콘")).isEqualTo("스콘");
		assertThat(classifier.classify("씨앗샤워도우")).isEqualTo("사워도우");
		assertThat(classifier.classify("원더풀명품찹쌀꽈배기")).isEqualTo("도넛");
		assertThat(classifier.classify("생딸기빙수")).isEqualTo("빙수");
	}

	@Test
	void 맛_단어가_아니라_마지막_제품어가_이긴다() {
		assertThat(classifier.classify("맘모스치즈케이크")).isEqualTo("케이크");
		assertThat(classifier.classify("케이크 타르트")).isEqualTo("타르트");
		assertThat(classifier.classify("쿠키앤크림케익")).isEqualTo("케이크");
	}

	@Test
	void 동률이면_더_구체적인_단어가_이긴다() {
		assertThat(classifier.classify("에그타르트")).isEqualTo("에그타르트"); // 타르트가 아니라
		assertThat(classifier.classify("옛날소보루")).isEqualTo("소보로");
	}

	@Test
	void 괄호_수식은_무시한다() {
		assertThat(classifier.classify("과일케이크 1호 (제철과일)")).isEqualTo("케이크");
		assertThat(classifier.classify("미니꽃설기(최소주문단위 10개)")).isEqualTo("떡");
	}

	@Test
	void 확장_MenuKind_로_해석한다() {
		// menu_category 테이블 미시딩 kind — 행 시딩 전엔 SQL 이 '기타'로 폴백
		assertThat(classifier.classify("마카롱")).isEqualTo("마카롱");
		assertThat(classifier.classify("뚱카롱")).isEqualTo("마카롱");
		assertThat(classifier.classify("플레인 크로플")).isEqualTo("크로플");
		assertThat(classifier.classify("티라미수")).isEqualTo("티라미수");
		assertThat(classifier.classify("두바이 쫀득쿠키 (55-60g)")).isEqualTo("쫀득쿠키");
		assertThat(classifier.classify("오레오쫀쿠")).isEqualTo("쫀득쿠키"); // 약칭
		assertThat(classifier.classify("두쫀쿠")).isEqualTo("쫀득쿠키");
		assertThat(classifier.classify("시나몬롤")).isEqualTo("롤");
		assertThat(classifier.classify("모카번")).isNull(); // 한 글자 '번' 만 걸리면 믿지 않는다 (2026-09-19 doubt)
		assertThat(classifier.classify("바닐라빈 티그레")).isEqualTo("티그레");
	}

	@Test
	void 추가_22종을_해석한다() {
		assertThat(classifier.classify("단팥빵")).isEqualTo("단팥빵");
		assertThat(classifier.classify("생크림 가득 모닝빵")).isEqualTo("모닝빵");
		assertThat(classifier.classify("사각 카스테라(초코)")).isEqualTo("카스테라");
		assertThat(classifier.classify("팥붕이 10마리 붕어빵")).isEqualTo("붕어빵");
		assertThat(classifier.classify("슈크림빵")).isEqualTo("크림빵");
		assertThat(classifier.classify("우유식빵")).isEqualTo("식빵");
	}

	@Test
	void 구체_kind_가_없으면_null_로_넘긴다() {
		// '빵' 그물 제거 — 본 앱 스케줄러가 NULL 인 메뉴를 AI 로 분류한다
		assertThat(classifier.classify("원준이엉덩이빵")).isNull();
		assertThat(classifier.classify("버섯 크림 리조또")).isNull();
		assertThat(classifier.classify("허니레몬차")).isNull();
	}

	@Test
	void 신규_kind_한과_초콜릿을_해석한다() {
		assertThat(classifier.classify("호두정과")).isEqualTo("한과");
		assertThat(classifier.classify("수제 호두강정")).isEqualTo("한과");
		assertThat(classifier.classify("코코넛전병")).isEqualTo("한과");
		assertThat(classifier.classify("생초콜릿")).isEqualTo("초콜릿");
		assertThat(classifier.classify("두바이초콜렛")).isEqualTo("초콜릿");
	}

	@Test
	void 쇼콜라로_끝나는_케이크류는_초콜릿이_아니다() {
		// '쇼콜라' 를 초콜릿 동의어로 넣으면 끝단어 규칙에 걸려 케이크류가 초콜릿으로 잡힌다
		assertThat(classifier.classify("퐁당쇼콜라")).isNull();
		assertThat(classifier.classify("쇼콜라퍼지")).isNull();
	}

	@Test
	void 신규_kind_머핀_다쿠아즈를_해석한다() {
		assertThat(classifier.classify("초코머핀")).isEqualTo("머핀");
		assertThat(classifier.classify("블루베리 머핀")).isEqualTo("머핀");
		assertThat(classifier.classify("다쿠아즈(카라멜다쿠아즈)")).isEqualTo("다쿠아즈");
		assertThat(classifier.classify("쑥 다크아즈")).isEqualTo("다쿠아즈");
	}

	@Test
	void 빵로그_실측에서_룰이_지던_표기를_해석한다() {
		// 본 앱 bread-record-categories.txt 와 같은 표다 — 한쪽을 고치면 다른 쪽도 (2026-09-09 보강)
		assertThat(classifier.classify("무화과시루")).isEqualTo("케이크");
		assertThat(classifier.classify("썸머요거트복숭아시루")).isEqualTo("케이크");
		assertThat(classifier.classify("인절미망고반반설빙")).isEqualTo("빙수");
		assertThat(classifier.classify("애플팥맘모스롤")).isEqualTo("맘모롤");
		assertThat(classifier.classify("블루베리 치즈케이크 샌드베이들")).isEqualTo("샌드베이글");
	}

	@Test
	void 아포가토는_가토보다_길어_아이스크림으로_간다() {
		assertThat(classifier.classify("초코아포가토")).isEqualTo("아이스크림");
		assertThat(classifier.classify("쑥갸또")).isEqualTo("케이크");
		assertThat(classifier.classify("쑥시루떡")).isNull(); // 케이크(시루)가 아니다 — 한 글자 '떡' 이라 AI 로 (2026-09-19 doubt)
	}

	@Test
	void 아포가토는_케이크_별칭_가토에_잡히지_않고_아이스크림이다() {
		// 2026-09-04 재분류 드라이런 실측: '빵' 버킷의 아포가토 ~860건이 케이크로 가려 했다
		assertThat(classifier.classify("아포가토")).isEqualTo("아이스크림");
		assertThat(classifier.classify("쑥 아포카토")).isEqualTo("아이스크림");
		assertThat(classifier.classify("갸또오쇼콜라")).isEqualTo("케이크"); // 진짜 갸또는 그대로
	}

	@Test
	void 한_글자나_샌드_단어만_걸리면_믿지_않는다() {
		// 다른 말의 일부이기 쉽다 — 2차 재분류 plan 표본(2026-09-19) 오판
		assertDoubted("키슈", "짧은 단어 '슈'");
		assertDoubted("1번 세트", "짧은 단어 '번'");
		assertDoubted("캐롤", "짧은 단어 '롤'");
		assertDoubted("떡갈비", "짧은 단어 '떡'");
		assertDoubted("버터샌드", "짧은 단어 '샌드'");
	}

	@Test
	void 닭강정은_한과가_아니다() {
		assertDoubted("닭강정", "닭강정");
		assertDoubted("순살 닭강정", "닭강정");
		assertThat(classifier.classify("오색쌀강정")).isEqualTo("한과");
	}

	@Test
	void 핫_아이스_초콜릿은_음료라_초콜릿이_아니다() {
		assertDoubted("핫초콜릿", "초콜릿 음료");
		assertDoubted("아이스 초콜렛", "초콜릿 음료");
		assertDoubted("HOT 초콜릿", "초콜릿 음료");
		assertThat(classifier.classify("생초콜릿")).isEqualTo("초콜릿");
	}

	@Test
	void 식사_오믈렛은_빵집_오믈렛이_아니다() {
		assertDoubted("오믈렛 라이스", "식사 오믈렛");
		assertDoubted("브런치 오믈렛", "식사 오믈렛");
		assertDoubted("오믈렛 플레이트", "식사 오믈렛");
		assertDoubted("베이컨 오믈렛", "식사 오믈렛");
		assertDoubted("샐러드 오믈렛", "식사 오믈렛");
		assertThat(classifier.classify("생딸기오믈렛")).isEqualTo("오믈렛");
		assertThat(classifier.classify("딸기오믈렛")).isEqualTo("오믈렛");
	}

	@Test
	void 끝말이_빵이면_앞의_속재료_단어를_믿지_않는다() {
		assertDoubted("인절미빵", "끝말이 ○○빵(종류 단어는 속재료)");
		assertDoubted("백설기빵", "끝말이 ○○빵(종류 단어는 속재료)");
		assertDoubted("슈크림찐빵", "끝말이 ○○빵(종류 단어는 속재료)");
		// 붙여 쓰면 크림빵인데 띄어 쓰면 슈 — 띄어쓰기에 따라 갈리는 판정도 믿지 않는다
		assertDoubted("바닐라 슈크림 빵", "끝말이 ○○빵(종류 단어는 속재료)");
		assertThat(classifier.classify("바닐라슈크림빵")).isEqualTo("크림빵");
	}

	@Test
	void 종류_단어_바로_뒤에_빵이_붙으면_그_종류다() {
		assertThat(classifier.classify("초코소보로빵")).isEqualTo("소보로");
		assertThat(classifier.classify("카라멜데니시빵")).isEqualTo("페이스트리");
		assertThat(classifier.doubt("초코소보로빵")).isEmpty();
		// 속재료 종류(떡·슈·파이·한과·초콜릿)는 바로 뒤에 빵이 붙어도 그 종류가 아니다
		assertDoubted("파이빵", "끝말이 ○○빵(종류 단어는 속재료)");
		assertDoubted("초콜릿빵", "끝말이 ○○빵(종류 단어는 속재료)");
	}

	@Test
	void 끝말이_종류_단어인_빵과_수량_꼬리는_그대로_분류한다() {
		assertThat(classifier.classify("앙버터 소금빵")).isEqualTo("소금빵");
		assertThat(classifier.classify("모닝빵 5개")).isEqualTo("모닝빵");
		assertThat(classifier.classify("모닝빵 ５개")).isEqualTo("모닝빵"); // 전각 숫자도 수량 꼬리다
		assertThat(classifier.classify("미니 소금빵 3입")).isEqualTo("소금빵");
		assertThat(classifier.classify("슈크림잉어빵")).isEqualTo("붕어빵");
		assertThat(classifier.classify("슈크림빵")).isEqualTo("크림빵");
	}

	@Test
	void 매핑_불가면_null_이다() {
		assertThat(classifier.classify("세레니티")).isNull(); // SQL 단계에서 '기타' 폴백
		assertThat(classifier.classify("상투과자")).isNull(); // kind 미추가 (빈도 낮아 보류)
		assertThat(classifier.classify(null)).isNull();
		assertThat(classifier.doubt(null)).isEmpty();
	}

	private void assertDoubted(String menuName, String reason) {
		assertThat(classifier.doubt(menuName)).as(menuName).isEqualTo(reason);
		assertThat(classifier.classify(menuName)).as(menuName).isNull();
	}
}
