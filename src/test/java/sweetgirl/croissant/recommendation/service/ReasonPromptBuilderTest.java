package sweetgirl.croissant.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.personalization.enums.TasteTag;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.enums.ReasonPromptVariant;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.TasteQuery;

/** 이유 문장 프롬프트 조립 (#662) — 네트워크 없이 변형별 결과 문자열을 그대로 본다. */
class ReasonPromptBuilderTest {

	private final ReasonPromptBuilder promptBuilder = new ReasonPromptBuilder(new RecommendationProperties());

	private static TasteQuery taste() {
		return new TasteQuery(0.62, 0.31, -0.55, 0.08,
			Map.of("동네빵집", 0.9, "아침", 0.4, "선물", 0.2), List.of("소금빵", "바게트"), 4);
	}

	private static CandidateStoreRow store(long storeId, String name, double tasteFit) {
		return new CandidateStoreRow(storeId, name, "성수동2가", 37.5, 127.05, null, 120, 3.4, 1.2, tasteFit, 5,
			0.5, 0.8, -0.2, 0.4, Map.of("동네빵집", 1.0, "데이트", 0.5), List.of("소금빵", "깜빠뉴"));
	}

	@Test
	@DisplayName("A 는 최소 재료만 넣는다 — 선정 근거 줄이 없다")
	void buildA_hasNoRuleEvidence() {
		String prompt = promptBuilder.build(ReasonPromptVariant.A, taste(),
			List.of(store(1L, "밀도", 0.71), store(2L, "베통", 0.12)), Map.of());

		assertThat(prompt).doesNotContain("선정 근거");
		assertThat(prompt).contains("밀도", "베통", "storeId=1", "storeId=2");
	}

	@Test
	@DisplayName("D 는 가게마다 룰의 선정 근거를 붙인다 — 겹치는 것만 적는다")
	void buildD_hasRuleEvidenceWithOverlapOnly() {
		String prompt = promptBuilder.build(ReasonPromptVariant.D, taste(), List.of(store(1L, "밀도", 0.71)),
			Map.of());

		assertThat(prompt).contains("선정 근거: 취향 일치도 0.71");
		// 회원 태그 셋 중 가게와 겹치는 것은 동네빵집 하나뿐이다
		assertThat(prompt).contains("겹치는 태그 동네빵집");
		assertThat(prompt).doesNotContain("겹치는 태그 동네빵집, 아침");
		assertThat(prompt).contains("겹치는 빵 종류 소금빵");
	}

	@Test
	@DisplayName("B 만 시그니처 메뉴를 넣는다 — 그 가게가 무엇으로 알려졌는지가 거기 있다")
	void buildB_addsSignatureMenus() {
		Map<Long, List<String>> menus = Map.of(1L, List.of("플레인 포카치아", "뺑 스위스", "잠봉뵈르"));

		String withMenus = promptBuilder.build(ReasonPromptVariant.B, taste(),
			List.of(store(1L, "에르제", 0.71)), menus);
		String without = promptBuilder.build(ReasonPromptVariant.A, taste(),
			List.of(store(1L, "에르제", 0.71)), menus);

		assertThat(withMenus).contains("   시그니처 메뉴: 플레인 포카치아, 뺑 스위스, 잠봉뵈르");
		assertThat(without).doesNotContain("   시그니처 메뉴:");
	}

	@Test
	@DisplayName("시그니처 메뉴가 없는 가게는 그 줄이 통째로 빠진다")
	void buildB_skipsMissingSignatureMenus() {
		String prompt = promptBuilder.build(ReasonPromptVariant.B, taste(),
			List.of(store(1L, "밀도", 0.71)), Map.of());

		assertThat(prompt).doesNotContain("   시그니처 메뉴:");
		assertThat(prompt).contains("빵 종류: 소금빵");
	}

	@Test
	@DisplayName("축 값과 축 이름은 프롬프트에 넣지 않는다 — 모델이 그 형용사를 그대로 문장으로 돌려준다")
	void axisIsNeverInPrompt() {
		String prompt = promptBuilder.build(ReasonPromptVariant.A, taste(), List.of(store(1L, "밀도", 0.71)),
			Map.of());

		assertThat(prompt).doesNotContain("담백", "바삭", "심플", "트렌디", "고전", "신상 빵", "축:");
		// 회원이 알아보는 것만 남는다
		assertThat(prompt).contains("- 좋아하는 태그: 동네빵집", "- 자주 먹는 빵 종류: 소금빵", "빵 종류: 소금빵");
	}

	@Test
	@DisplayName("가게 빵 종류 줄에서 일반 종류(빵 · 기타 · 요거트)를 뺀다 — 그것만 있으면 없음")
	void storeKinds_dropGenericKinds() {
		CandidateStoreRow mixed = new CandidateStoreRow(1L, "밀도", "성수동2가", 37.5, 127.05, null, 120, 3.4, 1.2, 0.71,
			5, 0.5, 0.8, -0.2, 0.4, Map.of(), List.of("빵", "소금빵", "기타", "깜빠뉴", "요거트"));
		CandidateStoreRow genericOnly = new CandidateStoreRow(2L, "베통", "성수동2가", 37.5, 127.05, null, 120, 3.4, 1.2,
			0.12, 5, 0.5, 0.8, -0.2, 0.4, Map.of(), List.of("빵", "기타"));

		String prompt = promptBuilder.build(ReasonPromptVariant.A, taste(), List.of(mixed, genericOnly),
			Map.of());

		assertThat(prompt).contains("   빵 종류: 소금빵, 깜빠뉴\n", "   빵 종류: 없음");
		assertThat(prompt).doesNotContain("빵 종류: 빵", "기타", "요거트");
	}

	@Test
	@DisplayName("강한 신호 가게가 4곳 미만이면 회원 태그를 없음으로 쓴다 — D 의 겹치는 태그도. 종류와 가게 태그 줄은 그대로")
	void thinEvidence_hidesMemberTags() {
		TasteQuery thin = new TasteQuery(0.62, 0.31, -0.55, 0.08, Map.of("동네빵집", 0.9), List.of("소금빵"), 3);

		String prompt = promptBuilder.build(ReasonPromptVariant.D, thin, List.of(store(1L, "밀도", 0.71)),
			Map.of());

		assertThat(prompt).contains("- 좋아하는 태그: 없음", "겹치는 태그 없음");
		assertThat(prompt).contains("- 자주 먹는 빵 종류: 소금빵", "겹치는 빵 종류 소금빵");
		// 가게 태그는 가게의 사실이라 회원 근거와 상관없이 남는다
		assertThat(prompt).contains("   태그: 동네빵집, 데이트");
	}

	@Test
	@DisplayName("태그도 종류도 없는 회원이면 없음이 된다")
	void emptyTaste_isWrittenAsNone() {
		String prompt = promptBuilder.build(ReasonPromptVariant.A, TasteQuery.empty(),
			List.of(store(1L, "밀도", 0)), Map.of());

		assertThat(prompt).contains("- 좋아하는 태그: 없음");
		assertThat(prompt).contains("- 자주 먹는 빵 종류: 없음");
		assertThat(prompt).doesNotContain("- 유형:");
	}

	@Test
	@DisplayName("쓸 수 있는 태그 목록은 TasteTag 에서 만든다 — 프롬프트 파일에 박아 두면 사전이 바뀔 때 어긋난다")
	void tagVocabulary_followsTasteTag() {
		for (ReasonPromptVariant variant : ReasonPromptVariant.values()) {
			String prompt = promptBuilder.build(variant, taste(), List.of(store(1L, "밀도", 0.71)), Map.of());

			assertThat(prompt).doesNotContain("{tagVocabulary}");
			for (TasteTag tag : TasteTag.values()) {
				assertThat(prompt).as("%s 변형에 %s 태그", variant, tag.getKey()).contains(tag.getKey());
			}
		}
	}

	@Test
	@DisplayName("가게 수와 임계값이 프롬프트에 그대로 박힌다")
	void placeholders_areReplaced() {
		String prompt = promptBuilder.build(ReasonPromptVariant.D, taste(),
			List.of(store(1L, "밀도", 0.71), store(2L, "베통", 0.12)), Map.of());

		assertThat(prompt).contains("[추천된 빵집 2곳]");
		assertThat(prompt).contains("0.20 를 넘으면");
		assertThat(prompt).doesNotContain("{storeCount}", "{taste}", "{stores}", "{tasteThreshold}");
	}

	@Test
	@DisplayName("회원 유형은 어떤 변형에도 넣지 않는다 — 유형 이름은 앱에서 없애기로 했고, 가게 쪽 근거 없이 유형과 가게를 잇게 된다")
	void typeIsNeverInPrompt() {
		for (ReasonPromptVariant variant : ReasonPromptVariant.values()) {
			String prompt = promptBuilder.build(variant, taste(), List.of(store(1L, "밀도", 0.71)),
				Map.of(1L, List.of("뺑 스위스")));

			assertThat(prompt).as(variant.name()).doesNotContain("유형");
		}
	}

	@Test
	@DisplayName("메뉴 이름이 없는 A · D 는 메뉴 이름을 지어내지 말라고 적는다 — A 가 지어내면 B(실제 메뉴 이름)와의 비교가 흐려진다")
	void menuNamesAreNotInvented_whereNotGiven() {
		for (ReasonPromptVariant variant : List.of(ReasonPromptVariant.A, ReasonPromptVariant.D)) {
			assertThat(promptBuilder.build(variant, taste(), List.of(store(1L, "밀도", 0.71)), Map.of()))
				.as(variant.name()).contains("메뉴 이름은 주어지지 않았다. 빵 종류 목록에 적힌 이름만 쓴다.");
		}
		assertThat(promptBuilder.build(ReasonPromptVariant.B, taste(), List.of(store(1L, "밀도", 0.71)),
			Map.of(1L, List.of("뺑 스위스")))).contains("메뉴 이름은 적힌 그대로 쓴다").doesNotContain("메뉴 이름은 주어지지 않았다");
	}
}
