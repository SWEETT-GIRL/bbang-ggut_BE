package sweetgirl.croissant.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.TasteQuery;

class AnchorBadgeResolverTest {

	/** 중심화를 끈 규칙 — 원값 곱으로 기여를 잰다. */
	private static final RecommendationProperties.Score RAW = rules(false);

	private static RecommendationProperties.Score rules(boolean centerAxes) {
		RecommendationProperties.Score score = new RecommendationProperties().getScore();
		score.setCenterAxes(centerAxes);
		return score;
	}

	/** 종류는 메뉴 종류이자 대표 종류로 넣는다 — 대부분의 테스트는 둘을 가를 필요가 없다 */
	private static CandidateStoreRow row(double tasteFit, Double taste, Double texture, Double filling, Double vibe,
			Map<String, Double> tags, List<String> kinds) {
		return new CandidateStoreRow(1L, "가게", "성수동", 37.5, 127.0, null, 100, 0, 1.0, tasteFit, 5,
			taste, texture, filling, vibe, tags, kinds, kinds);
	}

	/** 메뉴에는 있지만 대표 종류는 아닌 가게 */
	private static CandidateStoreRow menuOnly(List<String> menuKinds, List<String> signatureKinds) {
		return new CandidateStoreRow(1L, "가게", "성수동", 37.5, 127.0, null, 100, 0, 1.0, 0.9, 5,
			0.9, 0.9, 0.1, 0.1, Map.of(), menuKinds, signatureKinds);
	}

	@Test
	@DisplayName("① 사용자 상위 종류가 가게 종류에 있으면 '{종류} 취향' — 태그·축보다 먼저")
	void kindMatchFirst() {
		TasteQuery taste = new TasteQuery(0.8, 0.8, 0.5, 0.5, Map.of("머물기", 1.0), List.of("소금빵", "스콘"), 4, List.of());
		CandidateStoreRow store = row(0.9, 0.9, 0.9, 0.5, 0.5, Map.of("머물기", 0.9), List.of("케이크", "스콘"));

		assertThat(AnchorBadgeResolver.resolve(taste, store, RAW)).isEqualTo("스콘 취향");
	}

	@Test
	@DisplayName("① 종류 칩은 가게 대표 종류로 맞춘다 — 메뉴에 소금빵이 하나 있다고 '소금빵 취향' 을 붙이지 않는다")
	void kindMatchUsesSignatureKinds() {
		TasteQuery saltLover = new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of(), List.of("소금빵"), 0, List.of());

		assertThat(AnchorBadgeResolver.resolve(saltLover, menuOnly(List.of("소금빵", "케이크"), List.of("케이크")), RAW))
			.isEqualTo("담백");
		assertThat(AnchorBadgeResolver.resolve(saltLover, menuOnly(List.of("소금빵"), List.of("소금빵")), RAW))
			.isEqualTo("소금빵 취향");
	}

	@Test
	@DisplayName("① 기록 종류가 먼저, 그다음 추정 종류 — 추정 종류는 태그보다 앞선다")
	void inferredKindsComeAfterRecordedAndBeforeTags() {
		CandidateStoreRow bagelAndSalt = row(0.8, 0.9, 0.9, 0.1, 0.1, Map.of("가성비", 0.9), List.of("베이글", "소금빵"));
		TasteQuery both = new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of("가성비", 0.9), List.of("소금빵"), 4, List.of("베이글"));
		TasteQuery inferredOnly = new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of("가성비", 0.9), List.of(), 4, List.of("베이글"));
		TasteQuery tagOnly = new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of("가성비", 0.9), List.of(), 4, List.of("바게트"));

		assertThat(AnchorBadgeResolver.resolve(both, bagelAndSalt, RAW)).isEqualTo("소금빵 취향");
		assertThat(AnchorBadgeResolver.resolve(inferredOnly, bagelAndSalt, RAW)).isEqualTo("베이글 취향");
		assertThat(AnchorBadgeResolver.resolve(tagOnly, bagelAndSalt, RAW)).isEqualTo("가성비");
	}

	@Test
	@DisplayName("② 종류가 안 맞으면 둘 다 0.5 를 넘는 태그 중 곱이 가장 큰 태그의 표시 문구")
	void tagMatchSecond() {
		TasteQuery taste = new TasteQuery(0.8, 0.8, 0.5, 0.5, Map.of("머물기", 0.6, "가성비", 0.9, "디저트", 0.9),
			List.of("소금빵"), 4, List.of());
		CandidateStoreRow store = row(0.9, 0.9, 0.9, 0.5, 0.5,
			Map.of("머물기", 1.0, "가성비", 0.8, "디저트", 0.3), List.of("케이크"));

		// 가성비 0.9×0.8 = 0.72 > 머물기 0.6×1.0 = 0.6, 디저트는 가게 쪽이 0.5 이하라 빠진다
		assertThat(AnchorBadgeResolver.resolve(taste, store, RAW)).isEqualTo("가성비");
	}

	@Test
	@DisplayName("② 가게 문턱은 0.6 초과 — 0.5(보통 빵집 · 표 없는 가게)부터 0.6 까지는 표 몇 개 차이라 칩을 붙이지 않는다")
	void tagMatchNeedsStoreAboveThreshold() {
		TasteQuery lover = new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of("가성비", 0.9), List.of(), 4, List.of());
		CandidateStoreRow noVotes = row(0.8, 0.9, 0.9, 0.1, 0.1, Map.of("가성비", 0.5), List.of("케이크"));
		CandidateStoreRow atThreshold = row(0.8, 0.9, 0.9, 0.1, 0.1, Map.of("가성비", 0.6), List.of("케이크"));
		CandidateStoreRow standsOut = row(0.8, 0.9, 0.9, 0.1, 0.1, Map.of("가성비", 0.61), List.of("케이크"));

		// 칩이 안 붙으면 ③ 축 극 라벨로 내려간다
		assertThat(AnchorBadgeResolver.resolve(lover, noVotes, RAW)).isEqualTo("담백");
		assertThat(AnchorBadgeResolver.resolve(lover, atThreshold, RAW)).isEqualTo("담백");
		assertThat(AnchorBadgeResolver.resolve(lover, standsOut, RAW)).isEqualTo("가성비");
	}

	@Test
	@DisplayName("② 회원 문턱은 태그마다 다르다 — 모두에게 높은 특별한메뉴는 0.831 을 넘어야, 가성비는 0.5 를 넘으면 된다")
	void tagMatchUsesPerTagMemberThreshold() {
		CandidateStoreRow special = row(0.8, 0.9, 0.9, 0.1, 0.1, Map.of("특별한메뉴", 0.9), List.of("케이크"));
		CandidateStoreRow value = row(0.8, 0.9, 0.9, 0.1, 0.1, Map.of("가성비", 0.9), List.of("케이크"));

		assertThat(AnchorBadgeResolver.resolve(
			new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of("특별한메뉴", 0.8), List.of(), 4, List.of()),
			special, RAW)).isEqualTo("담백");
		assertThat(AnchorBadgeResolver.resolve(
			new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of("특별한메뉴", 0.85), List.of(), 4, List.of()),
			special, RAW)).isEqualTo("특별한 메뉴");
		assertThat(
			AnchorBadgeResolver.resolve(new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of("가성비", 0.5), List.of(), 4, List.of()),
				value, RAW))
			.isEqualTo("담백");
		assertThat(AnchorBadgeResolver.resolve(
			new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of("가성비", 0.51), List.of(), 4, List.of()),
			value, RAW)).isEqualTo("가성비");
	}

	@Test
	@DisplayName("② 문턱은 설정값이다 — 가게 문턱을 0.7 로 올리면 0.65 인 가게에는 칩이 붙지 않는다")
	void tagMatchThresholdIsConfigurable() {
		RecommendationProperties.Score stricter = rules(false);
		stricter.setStoreNotableTagAbove(0.7);
		TasteQuery lover = new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of("가성비", 0.9), List.of(), 4, List.of());
		CandidateStoreRow slightlyAbove = row(0.8, 0.9, 0.9, 0.1, 0.1, Map.of("가성비", 0.65), List.of("케이크"));
		CandidateStoreRow clearlyAbove = row(0.8, 0.9, 0.9, 0.1, 0.1, Map.of("가성비", 0.8), List.of("케이크"));

		assertThat(AnchorBadgeResolver.resolve(lover, slightlyAbove, RAW)).isEqualTo("가성비");
		assertThat(AnchorBadgeResolver.resolve(lover, slightlyAbove, stricter)).isEqualTo("담백");
		assertThat(AnchorBadgeResolver.resolve(lover, clearlyAbove, stricter)).isEqualTo("가성비");
	}

	@Test
	@DisplayName("② 태그 칩은 데이터 키가 아니라 표시 문구다 — 머물기 → '머물기 좋은', 공간사진 → '사진'")
	void tagMatchShowsLabel() {
		TasteQuery taste = new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of("머물기", 0.9), List.of(), 4, List.of());
		CandidateStoreRow stay = row(0.8, 0.9, 0.9, 0.1, 0.1, Map.of("머물기", 0.8), List.of("케이크"));
		assertThat(AnchorBadgeResolver.resolve(taste, stay, RAW)).isEqualTo("머물기 좋은");

		TasteQuery photoLover = new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of("공간사진", 0.9), List.of(), 4, List.of());
		CandidateStoreRow photo = row(0.8, 0.9, 0.9, 0.1, 0.1, Map.of("공간사진", 0.8), List.of("케이크"));
		assertThat(AnchorBadgeResolver.resolve(photoLover, photo, RAW)).isEqualTo("사진");
	}

	@Test
	@DisplayName("② 강한 신호 가게가 4곳 미만인 회원은 태그 배지를 건너뛰고 ③ 으로 내려간다 — 한두 곳의 태그를 옮겨 적은 것이라서")
	void tagMatchSkippedWithoutEnoughStrongStores() {
		CandidateStoreRow store = row(0.8, 0.9, 0.9, 0.1, 0.1, Map.of("가성비", 0.8), List.of("케이크"));
		TasteQuery enough = new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of("가성비", 0.9), List.of(), 4, List.of());
		TasteQuery thin = new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of("가성비", 0.9), List.of(), 3, List.of());

		assertThat(AnchorBadgeResolver.resolve(enough, store, RAW)).isEqualTo("가성비");
		assertThat(AnchorBadgeResolver.resolve(thin, store, RAW)).isEqualTo("담백");
	}

	@Test
	@DisplayName("③ 종류·태그가 없으면 코사인이 임계를 넘을 때 기여가 가장 큰 축의 사용자 극 라벨")
	void axisPoleThird() {
		TasteQuery salty = new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of(), List.of(), 0, List.of());
		CandidateStoreRow store = row(0.8, 0.9, 0.9, 0.1, 0.1, Map.of(), List.of("케이크"));
		assertThat(AnchorBadgeResolver.resolve(salty, store, RAW)).isEqualTo("담백");

		TasteQuery sweetSoft = new TasteQuery(-0.3, -0.9, 0.0, 0.0, Map.of(), List.of(), 0, List.of());
		CandidateStoreRow softStore = row(0.8, -0.3, -0.9, 0.0, 0.0, Map.of(), List.of());
		assertThat(AnchorBadgeResolver.resolve(sweetSoft, softStore, RAW)).isEqualTo("폭신");
	}

	@Test
	@DisplayName("④ 축이 없거나 코사인이 낮으면 '이 동네 시그니처'")
	void signatureLast() {
		TasteQuery taste = new TasteQuery(0.9, 0.2, 0.1, 0.1, Map.of(), List.of(), 0, List.of());
		assertThat(AnchorBadgeResolver.resolve(taste, row(0.1, 0.9, 0.9, 0.1, 0.1, Map.of(), List.of()), RAW))
			.isEqualTo("이 동네 시그니처");
		assertThat(AnchorBadgeResolver.resolve(taste, row(0.9, null, null, null, null, Map.of(), List.of()), RAW))
			.isEqualTo("이 동네 시그니처");
		assertThat(AnchorBadgeResolver.resolve(TasteQuery.empty(), row(0.0, 0.9, 0.9, 0.1, 0.1, Map.of(), List.of()),
			RAW)).isEqualTo("이 동네 시그니처");
	}

	@Test
	@DisplayName("넷째 축 라벨은 앱 검사 선택지 그대로 '클래식 / 트렌디' — 서버 안 이름 '고전 / 신상' 을 칩에 쓰지 않는다")
	void axisPole_vibeUsesQuizWords() {
		CandidateStoreRow vibeStore = row(0.8, 0.0, 0.0, 0.0, 0.9, Map.of(), List.of());
		TasteQuery trendy = new TasteQuery(0.0, 0.0, 0.0, 0.9, Map.of(), List.of(), 0, List.of());
		assertThat(AnchorBadgeResolver.resolve(trendy, vibeStore, RAW)).isEqualTo("트렌디");

		CandidateStoreRow classicStore = row(0.8, 0.0, 0.0, 0.0, -0.9, Map.of(), List.of());
		TasteQuery classic = new TasteQuery(0.0, 0.0, 0.0, -0.9, Map.of(), List.of(), 0, List.of());
		assertThat(AnchorBadgeResolver.resolve(classic, classicStore, RAW)).isEqualTo("클래식");
	}

	@Test
	@DisplayName("중심화가 켜져 있으면 기여도 (사용자−평균)·(가게−평균)으로 잰다 — 원값 곱이 전부 0 인 가게도 점수를 만든 축을 말한다")
	void axisPole_usesCenteredContributionsWhenCenteringOn() {
		// PR #739 리뷰 예: 사용자 (−0.7, −0.7, 0.7, 0.7), 가게 (0, 0, 0, 0), 기본 중심값 → 분위기 축 기여 +0.686 이 최대, 중심화 코사인 ≈ 0.53
		TasteQuery taste = new TasteQuery(-0.7, -0.7, 0.7, 0.7, Map.of(), List.of(), 0, List.of());
		CandidateStoreRow neutral = row(0.53, 0.0, 0.0, 0.0, 0.0, Map.of(), List.of());

		assertThat(AnchorBadgeResolver.resolve(taste, neutral, rules(true))).isEqualTo("트렌디");
		// 같은 값을 원값으로 재면 곱이 전부 0 이라 첫 축(달콤)이 나온다 — 중심화를 끈 규칙에서만 그렇다
		assertThat(AnchorBadgeResolver.dominantPole(taste, neutral, RAW)).isEqualTo("달콤");
	}
}
