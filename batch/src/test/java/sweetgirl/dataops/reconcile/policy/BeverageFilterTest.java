package sweetgirl.dataops.reconcile.policy;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BeverageFilterTest {

	private final BeverageFilter beverageFilter = new BeverageFilter();

	@Test
	void 배민_그룹명이_음료면_제외한다() {
		assertThat(beverageFilter.isExcluded("따뜻한 아이스크림", "COFFEE")).isTrue();
		assertThat(beverageFilter.isExcluded("수제청", "에이드")).isTrue();
		assertThat(beverageFilter.isExcluded("자몽허니블랙", "TEA")).isTrue();
		assertThat(beverageFilter.isExcluded("자몽허니블랙", "티")).isTrue();
	}

	@Test
	void 그룹명이_디저트면_통과한다() {
		assertThat(beverageFilter.isExcluded("티라미수", "디저트")).isFalse();
		assertThat(beverageFilter.isExcluded("스콘", "BAKERY")).isFalse();
		assertThat(beverageFilter.isExcluded("찹쌀떡", "찰떡류")).isFalse();
	}

	@Test
	void 끝_제품어가_음료면_음료로_잡는다() {
		assertThat(beverageFilter.isExcluded("카페라떼", null)).isTrue();
		assertThat(beverageFilter.isExcluded("흑임자라떼", null)).isTrue();
		assertThat(beverageFilter.isExcluded("토마토주스", null)).isTrue();
		assertThat(beverageFilter.isExcluded("레몬소다", null)).isTrue();
		assertThat(beverageFilter.isExcluded("아인슈페너", null)).isTrue();
		assertThat(beverageFilter.isExcluded("천혜향 스무디", null)).isTrue();
		assertThat(beverageFilter.isExcluded("아메리카노", null)).isTrue();
	}

	@Test
	void 맛이_디저트여도_끝이_음료면_음료다() {
		// 이전 버그: 맛 이름(초콜릿·쿠키·티라미수·와플)에 걸려 라떼 음료를 통과시키던 것
		assertThat(beverageFilter.isExcluded("초콜릿라떼", null)).isTrue();
		assertThat(beverageFilter.isExcluded("쿠키크림라떼", null)).isTrue();
		assertThat(beverageFilter.isExcluded("티라미수 라떼", null)).isTrue();
		assertThat(beverageFilter.isExcluded("와플라떼", null)).isTrue();
		assertThat(beverageFilter.isExcluded("쇼콜라라떼", null)).isTrue();
	}

	@Test
	void 끝_제품어가_빵_디저트면_통과한다() {
		assertThat(beverageFilter.isExcluded("소금빵", null)).isFalse();
		assertThat(beverageFilter.isExcluded("커피번", null)).isFalse();
		assertThat(beverageFilter.isExcluded("커피콩빵", null)).isFalse();
		assertThat(beverageFilter.isExcluded("아라비카커피빵", null)).isFalse();
		assertThat(beverageFilter.isExcluded("커피크림치즈 쿠키", null)).isFalse();
		assertThat(beverageFilter.isExcluded("커피마들렌", null)).isFalse();
		assertThat(beverageFilter.isExcluded("녹차 마들렌", null)).isFalse();
		assertThat(beverageFilter.isExcluded("티라미수", null)).isFalse();
	}

	@Test
	void 맛이_음료여도_끝이_디저트면_디저트다() {
		// "라떼 마들렌"·"초코라떼 케이크" 처럼 음료 맛의 디저트는 통과해야 한다
		assertThat(beverageFilter.isExcluded("라떼 마들렌", null)).isFalse();
		assertThat(beverageFilter.isExcluded("초코라떼 케이크", null)).isFalse();
		assertThat(beverageFilter.isExcluded("아메리카노 쿠키", null)).isFalse();
	}

	@Test
	void 세트_메뉴는_제품어가_없으면_통과한다() {
		assertThat(beverageFilter.isExcluded("1인 베이글 세트", null)).isFalse();
		assertThat(beverageFilter.isExcluded("골라담기 16구+음료2잔", null)).isFalse();
	}

	// ---- 이하 2026-08-13 확장: menuadd 표본 40곳 실검수 누수 (matching/menuadd_sample_judgments.jsonl) ----

	@Test
	void 티_차_전통음료를_제외한다() {
		assertThat(beverageFilter.isExcluded("자몽티", null)).isTrue();
		assertThat(beverageFilter.isExcluded("허브티", null)).isTrue();
		assertThat(beverageFilter.isExcluded("자몽블랙티", null)).isTrue();
		assertThat(beverageFilter.isExcluded("유자차", null)).isTrue();
		assertThat(beverageFilter.isExcluded("수제대추차", null)).isTrue();
		assertThat(beverageFilter.isExcluded("쌍화차", null)).isTrue();
		assertThat(beverageFilter.isExcluded("페퍼민트유자차", null)).isTrue();
		assertThat(beverageFilter.isExcluded("레몬 생강차", null)).isTrue();
		assertThat(beverageFilter.isExcluded("애플시나몬수정과", null)).isTrue();
		assertThat(beverageFilter.isExcluded("식혜 (뚱캔)(1개)", null)).isTrue();
		assertThat(beverageFilter.isExcluded("뱅쇼", null)).isTrue();
		assertThat(beverageFilter.isExcluded("칡", null)).isTrue();
	}

	@Test
	void 티_음절이_들어간_디저트는_통과한다() {
		assertThat(beverageFilter.isExcluded("비스코티", null)).isFalse(); // 티로 끝나지만 디저트어
		assertThat(beverageFilter.isExcluded("바닐라빈 티그레", null)).isFalse();
		assertThat(beverageFilter.isExcluded("세레니티", null)).isFalse(); // 시그니처 디저트명 보호
		assertThat(beverageFilter.isExcluded("애플시나몬 파이", null)).isFalse();
	}

	@Test
	void 허브차_이름은_끝단어일_때만_음료다() {
		assertThat(beverageFilter.isExcluded("얼그레이", null)).isTrue();
		assertThat(beverageFilter.isExcluded("페퍼민트", null)).isTrue();
		assertThat(beverageFilter.isExcluded("캐모마일", null)).isTrue();
		assertThat(beverageFilter.isExcluded("레몬밤 캐모마일", null)).isTrue();
		assertThat(beverageFilter.isExcluded("망고 루이보스", null)).isTrue();
		assertThat(beverageFilter.isExcluded("티바나 캐모마일,민트,히비스커스", null)).isTrue();
		assertThat(beverageFilter.isExcluded("잉글리쉬 브렉퍼스트", null)).isTrue();
		// 맛으로 쓰였으면 통과
		assertThat(beverageFilter.isExcluded("얼그레이 밀푀유", null)).isFalse();
		assertThat(beverageFilter.isExcluded("얼그레이스콘", null)).isFalse();
		assertThat(beverageFilter.isExcluded("마들렌 (레몬, 코코, 얼그레이 등)", null)).isFalse();
	}

	@Test
	void 우유_밀크는_끝단어일_때만_음료다() {
		assertThat(beverageFilter.isExcluded("크림 초코우유", null)).isTrue();
		assertThat(beverageFilter.isExcluded("쑥 우유(300ml)", null)).isTrue();
		assertThat(beverageFilter.isExcluded("오트밀크", null)).isTrue();
		assertThat(beverageFilter.isExcluded("와일드 말차", null)).isTrue();
		assertThat(beverageFilter.isExcluded("와일드 말차(ONLY ICED)", null)).isTrue();
		// 맛으로 쓰였으면 통과
		assertThat(beverageFilter.isExcluded("밀크롤케이크", null)).isFalse();
		assertThat(beverageFilter.isExcluded("우유식빵", null)).isFalse();
		assertThat(beverageFilter.isExcluded("인절미우유빙수(여름한정메뉴)", null)).isFalse();
		assertThat(beverageFilter.isExcluded("말차클래식", null)).isFalse();
		assertThat(beverageFilter.isExcluded("말차 팥 빙수", null)).isFalse();
	}

	@Test
	void 캔음료는_괄호_단위까지_검사한다() {
		assertThat(beverageFilter.isExcluded("한라봉 (슬림캔)(1개)", null)).isTrue();
		assertThat(beverageFilter.isExcluded("망고 (슬림캔)(1개)", null)).isTrue();
		assertThat(beverageFilter.isExcluded("쿨피스 복숭아 (슬림캔)(1개)", null)).isTrue();
		assertThat(beverageFilter.isExcluded("블랜딩 티(tea)", null)).isTrue();
		assertThat(beverageFilter.isExcluded("애플시나몬티(시즌메뉴)", null)).isTrue();
		// 괄호가 맛·구성 설명이면 통과
		assertThat(beverageFilter.isExcluded("말렌카 케익(호두,초코,레몬)", null)).isFalse();
		assertThat(beverageFilter.isExcluded("과일산도(요거트 크림)", null)).isFalse();
		assertThat(beverageFilter.isExcluded("치즈볼(모짜렐라)(3개)", null)).isFalse();
	}

	@Test
	void 원두_드립_커피상품을_제외한다() {
		assertThat(beverageFilter.isExcluded("원두판매", null)).isTrue();
		assertThat(beverageFilter.isExcluded("월간브루잉", null)).isTrue();
		assertThat(beverageFilter.isExcluded("핸드 드립 / 스페셜티", null)).isTrue();
		assertThat(beverageFilter.isExcluded("스페셜티배치브루ONLY TAKEOUT", null)).isTrue();
		assertThat(beverageFilter.isExcluded("에티오피아", null)).isTrue();
		assertThat(beverageFilter.isExcluded("A 에티오피아 고구구 와시드 라이트", null)).isTrue();
		assertThat(beverageFilter.isExcluded("D 콜롬비아 마운틴 워터 디카페인", null)).isTrue();
		assertThat(beverageFilter.isExcluded("카라멜 마끼야또", null)).isTrue();
		assertThat(beverageFilter.isExcluded("착즙 레모네이드", null)).isTrue();
		assertThat(beverageFilter.isExcluded("착즙 자몽.오렌지당근,비트당근", null)).isTrue();
		assertThat(beverageFilter.isExcluded("NEW 자몽 스파클링", null)).isTrue();
		assertThat(beverageFilter.isExcluded("하이볼", null)).isTrue();
	}

	// ---- 이하 2026-08-13 2차: 전수(94,350건) 스캔 누수·함정 (재판정 1차 후) ----

	@Test
	void 이차_스캔_음료_누수를_제외한다() {
		assertThat(beverageFilter.isExcluded("아샷추", null)).isTrue();
		assertThat(beverageFilter.isExcluded("아샷추(only ice)", null)).isTrue();
		assertThat(beverageFilter.isExcluded("보리 미숫가루", null)).isTrue();
		assertThat(beverageFilter.isExcluded("카페라테", null)).isTrue();
		assertThat(beverageFilter.isExcluded("말차라테", null)).isTrue();
		assertThat(beverageFilter.isExcluded("탄산음료(콜라/사이다)", null)).isTrue();
		assertThat(beverageFilter.isExcluded("콜라", null)).isTrue();
		assertThat(beverageFilter.isExcluded("모든음료", null)).isTrue();
		assertThat(beverageFilter.isExcluded("[ 음료 ]", null)).isTrue();
		assertThat(beverageFilter.isExcluded("흑당 버블티 ICE", null)).isTrue();
		assertThat(beverageFilter.isExcluded("피치우롱", null)).isTrue();
		assertThat(beverageFilter.isExcluded("히비스커스 엘더베리", null)).isTrue();
		assertThat(beverageFilter.isExcluded("허브티백", null)).isTrue();
		assertThat(beverageFilter.isExcluded("진저에일", null)).isTrue();
		assertThat(beverageFilter.isExcluded("따뜻한 한방 쌍화탕", null)).isTrue();
	}

	@Test
	void 주류를_제외한다() {
		assertThat(beverageFilter.isExcluded("글라스 와인", null)).isTrue();
		assertThat(beverageFilter.isExcluded("생맥주", null)).isTrue();
		assertThat(beverageFilter.isExcluded("다양한 칵테일", null)).isTrue();
		assertThat(beverageFilter.isExcluded("소주", null)).isTrue();
		assertThat(beverageFilter.isExcluded("너드바질막걸리", null)).isTrue();
	}

	@Test
	void 이차_스캔_비식품을_제외한다() {
		assertThat(beverageFilter.isExcluded("수제청", null)).isTrue();
		assertThat(beverageFilter.isExcluded("레몬생강청", null)).isTrue();
		assertThat(beverageFilter.isExcluded("원데이클래스", null)).isTrue();
		assertThat(beverageFilter.isExcluded("베이킹 클래스", null)).isTrue();
		assertThat(beverageFilter.isExcluded("미니 캔들(1개)", null)).isTrue();
		assertThat(beverageFilter.isExcluded("비누꽃다발", null)).isTrue();
	}

	@Test
	void 이차_스캔_함정_디저트는_통과한다() {
		assertThat(beverageFilter.isExcluded("쇼콜라 샹티", null)).isFalse(); // 티로 끝나지만 샹티(크림)
		assertThat(beverageFilter.isExcluded("[whole] 쇼콜라 샹티", null)).isFalse();
		assertThat(beverageFilter.isExcluded("뺑오쇼콜라", null)).isFalse(); // 콜라로 끝나지만 쇼콜라
		assertThat(beverageFilter.isExcluded("퐁당 쇼콜라", null)).isFalse();
		assertThat(beverageFilter.isExcluded("소주케이크", null)).isFalse(); // 소주병 모양 케이크
		assertThat(beverageFilter.isExcluded("막걸리 에그타르트", null)).isFalse();
		assertThat(beverageFilter.isExcluded("클래식초코", null)).isFalse(); // '클래스' 아님
		assertThat(beverageFilter.isExcluded("화이트크런키링", null)).isFalse(); // 키링이 아니라 크런키 링
		assertThat(beverageFilter.isExcluded("미니꽃설기(최소주문단위 10개)", null)).isFalse(); // 괄호 안 '소주' 오염 방지
		assertThat(beverageFilter.isExcluded("그린티빙수", null)).isFalse();
		assertThat(beverageFilter.isExcluded("생과일케이크", null)).isFalse();
		assertThat(beverageFilter.isExcluded("흑당치즈토스트", null)).isFalse();
		assertThat(beverageFilter.isExcluded("레드와인무화과 스콘", null)).isFalse();
		assertThat(beverageFilter.isExcluded("밀크쉐이크 크림 도넛", null)).isFalse();
		assertThat(beverageFilter.isExcluded("미숫가루 인절미", null)).isFalse();
		assertThat(beverageFilter.isExcluded("아포카토", null)).isFalse();
	}

	@Test
	void 용량_테이크아웃_표기는_병입_음료_신호로_제외한다() {
		// 2026-08-14 store 311231 실측 — ml/리터/L·take out 표기 354+14건 전량 음료·보틀
		assertThat(beverageFilter.isExcluded("더치원액(500ml)", null)).isTrue();
		assertThat(beverageFilter.isExcluded("커하쪼꼬미(딸기우유350ml)", null)).isTrue();
		assertThat(beverageFilter.isExcluded("얼그레이티 1리터", null)).isTrue();
		assertThat(beverageFilter.isExcluded("[특가할인] 1리터 보틀 모음(1L 보틀 (Only ice))", null)).isTrue();
		assertThat(beverageFilter.isExcluded("내열유리컵(430ml)", null)).isTrue();
		assertThat(beverageFilter.isExcluded("딸바[take out]", null)).isTrue();
		assertThat(beverageFilter.isExcluded("딸기요거트[take out]", null)).isTrue();
		assertThat(beverageFilter.isExcluded("테이크아웃 아메리카노", null)).isTrue();
		// 용량 표기 없는 일반 디저트는 영향 없음 — 'l' 단독은 단어 경계에서만
		assertThat(beverageFilter.isExcluded("마들렌(2개입)", null)).isFalse();
		assertThat(beverageFilter.isExcluded("두바이쫀득쿠키(50g)1인2개구매가능", null)).isFalse();
	}

	@Test
	void 식사류_비식품을_제외한다() {
		assertThat(beverageFilter.isExcluded("새우 투움바 파스타", null)).isTrue();
		assertThat(beverageFilter.isExcluded("베이컨 크림 파스타", null)).isTrue();
		assertThat(beverageFilter.isExcluded("궁물마늘떡볶이밀키트", null)).isTrue();
		assertThat(beverageFilter.isExcluded("포장박스", null)).isTrue();
		assertThat(beverageFilter.isExcluded("웨딩 문의", null)).isTrue();
		assertThat(beverageFilter.isExcluded("백일, 돌상차림", null)).isTrue();
		// 베이커리 인접 식품(핫도그·고로케·파니니·토스트)은 서비스 범위 — 표본 검수 기준
		assertThat(beverageFilter.isExcluded("햄치즈 핫도그(1개)", null)).isFalse();
		assertThat(beverageFilter.isExcluded("고로케(순한맛)", null)).isFalse();
		assertThat(beverageFilter.isExcluded("바질치킨파니니", null)).isFalse();
		assertThat(beverageFilter.isExcluded("그릴드치즈토스티", null)).isFalse();
		assertThat(beverageFilter.isExcluded("치아커 피크닉 박스", null)).isFalse(); // 디저트 선물 구성
	}

	// ---- 2026-09-19 식사 확장 (dataops #11): 초기 적재분에 남아 있던 식사 메뉴 ----

	@Test
	void 확장한_식사류를_제외한다() {
		assertThat(beverageFilter.isExcluded("리코타치즈 샐러드", null)).isTrue();
		assertThat(beverageFilter.isExcluded("고르곤졸라 피자", null)).isTrue();
		assertThat(beverageFilter.isExcluded("김치볶음밥", null)).isTrue();
		assertThat(beverageFilter.isExcluded("치킨텐더 샐러드", null)).isTrue();
		assertThat(beverageFilter.isExcluded("나폴리 모짜렐라버거 세트", null)).isTrue();
		assertThat(beverageFilter.isExcluded("허니갈릭간장 치킨 1조각(클래식)", null)).isTrue();
		assertThat(beverageFilter.isExcluded("한돈 치즈 돈까스 도시락", null)).isTrue(); // 도시락은 안 넣었지만 돈까스로 걸린다
		assertThat(beverageFilter.isExcluded("크림 까르보나라", null)).isTrue();
		assertThat(beverageFilter.isExcluded("왕만두", null)).isTrue();
		assertThat(beverageFilter.isExcluded("월령브런치", null)).isTrue();
	}

	@Test
	void 식사어가_앞에_있어도_끝이_빵_디저트면_통과한다() {
		assertThat(beverageFilter.isExcluded("피자빵", null)).isFalse();
		assertThat(beverageFilter.isExcluded("불닭 피자 식빵", null)).isFalse();
		assertThat(beverageFilter.isExcluded("치킨 샌드위치", null)).isFalse();
		assertThat(beverageFilter.isExcluded("카레빵", null)).isFalse();
		assertThat(beverageFilter.isExcluded("에그 샐러드 크루아상", null)).isFalse();
		assertThat(beverageFilter.isExcluded("샐러드빵", null)).isFalse();
		assertThat(beverageFilter.isExcluded("만두 찐빵", null)).isFalse();
		// 식사 단어로 넣지 않은 것 — 운영 표본에서 빵집 상품이었다
		assertThat(beverageFilter.isExcluded("도시락레터링(지름 10cm)", null)).isFalse(); // 도시락 케이크
		assertThat(beverageFilter.isExcluded("약식도시락(예약주문 부탁드립니다)", null)).isFalse();
		assertThat(beverageFilter.isExcluded("다크빨미카레", null)).isFalse();
		assertThat(beverageFilter.isExcluded("추억의 라면땅", null)).isFalse();
	}

	@Test
	void 스프_수프는_끝단어일_때만_식사다() {
		assertThat(beverageFilter.isExcluded("양송이스프", null)).isTrue();
		assertThat(beverageFilter.isExcluded("단호박 스프", null)).isTrue();
		assertThat(beverageFilter.isExcluded("[고소] 양송이 수프", null)).isTrue();
		assertThat(beverageFilter.isExcluded("옥수수스프(1인)", null)).isTrue();
		// 위치 무관으로 보면 걸리던 것 — 스프링클·스프레드
		assertThat(beverageFilter.isExcluded("핑크스프링클", null)).isFalse();
		assertThat(beverageFilter.isExcluded("카이막 스프레드", null)).isFalse();
		assertThat(beverageFilter.isExcluded("크리스프(백악관쿠키)", null)).isFalse();
	}
}
