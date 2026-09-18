package sweetgirl.croissant.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.enums.ReasonPromptVariant;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.TasteQuery;

/** 이유 문장 프롬프트 조립 (#662) — 네트워크 없이 변형별 결과 문자열을 그대로 본다. */
class ReasonPromptBuilderTest {

	private final ReasonPromptBuilder promptBuilder = new ReasonPromptBuilder(new RecommendationProperties());

	private static TasteQuery taste() {
		return new TasteQuery(0.62, 0.31, -0.55, 0.08,
			Map.of("동네빵집", 0.9, "아침", 0.4, "선물", 0.2), List.of("소금빵", "바게트"));
	}

	private static CandidateStoreRow store(long storeId, String name, double tasteFit) {
		return new CandidateStoreRow(storeId, name, "성수동2가", 37.5, 127.05, null, 120, 3.4, 1.2, tasteFit, 5,
			0.5, 0.8, -0.2, 0.4, Map.of("동네빵집", 1.0, "데이트", 0.5), List.of("소금빵", "깜빠뉴"));
	}

	@Test
	@DisplayName("A 는 최소 재료만 넣는다 — 선정 근거 줄이 없다")
	void buildA_hasNoRuleEvidence() {
		String prompt = promptBuilder.build(ReasonPromptVariant.A, taste(), "PCLN",
			List.of(store(1L, "밀도", 0.71), store(2L, "베통", 0.12)), Map.of());

		assertThat(prompt).doesNotContain("선정 근거");
		assertThat(prompt).contains("밀도", "베통", "storeId=1", "storeId=2");
	}

	@Test
	@DisplayName("D 는 가게마다 룰의 선정 근거를 붙인다 — 겹치는 것만 적는다")
	void buildD_hasRuleEvidenceWithOverlapOnly() {
		String prompt = promptBuilder.build(ReasonPromptVariant.D, taste(), "PCLN", List.of(store(1L, "밀도", 0.71)),
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

		String withMenus = promptBuilder.build(ReasonPromptVariant.B, taste(), "PCLN",
			List.of(store(1L, "에르제", 0.71)), menus);
		String without = promptBuilder.build(ReasonPromptVariant.A, taste(), "PCLN",
			List.of(store(1L, "에르제", 0.71)), menus);

		assertThat(withMenus).contains("   시그니처 메뉴: 플레인 포카치아, 뺑 스위스, 잠봉뵈르");
		assertThat(without).doesNotContain("   시그니처 메뉴:");
	}

	@Test
	@DisplayName("시그니처 메뉴가 없는 가게는 그 줄이 통째로 빠진다")
	void buildB_skipsMissingSignatureMenus() {
		String prompt = promptBuilder.build(ReasonPromptVariant.B, taste(), "PCLN",
			List.of(store(1L, "밀도", 0.71)), Map.of());

		assertThat(prompt).doesNotContain("   시그니처 메뉴:");
		assertThat(prompt).contains("빵 종류: 소금빵");
	}

	@Test
	@DisplayName("축 값과 축 이름은 프롬프트에 넣지 않는다 — 모델이 그 형용사를 그대로 문장으로 돌려준다")
	void axisIsNeverInPrompt() {
		String prompt = promptBuilder.build(ReasonPromptVariant.A, taste(), "PCLN", List.of(store(1L, "밀도", 0.71)),
			Map.of());

		assertThat(prompt).doesNotContain("담백", "바삭", "심플", "트렌디", "고전", "신상 빵", "축:");
		// 회원이 알아보는 것만 남는다
		assertThat(prompt).contains("- 좋아하는 태그: 동네빵집", "- 자주 먹는 빵 종류: 소금빵", "빵 종류: 소금빵");
	}

	@Test
	@DisplayName("태그도 종류도 없는 회원이면 없음이 된다")
	void emptyTaste_isWrittenAsNone() {
		String prompt = promptBuilder.build(ReasonPromptVariant.A, TasteQuery.empty(), null,
			List.of(store(1L, "밀도", 0)), Map.of());

		assertThat(prompt).contains("- 좋아하는 태그: 없음");
		assertThat(prompt).contains("- 자주 먹는 빵 종류: 없음");
		assertThat(prompt).doesNotContain("- 유형:");
	}

	@Test
	@DisplayName("가게 수와 임계값이 프롬프트에 그대로 박힌다")
	void placeholders_areReplaced() {
		String prompt = promptBuilder.build(ReasonPromptVariant.D, taste(), "PCLN",
			List.of(store(1L, "밀도", 0.71), store(2L, "베통", 0.12)), Map.of());

		assertThat(prompt).contains("[추천된 빵집 2곳]");
		assertThat(prompt).contains("0.20 를 넘으면");
		assertThat(prompt).doesNotContain("{storeCount}", "{taste}", "{stores}", "{tasteThreshold}");
	}

	@Test
	@DisplayName("유형 코드는 화면 이름으로 바뀐다")
	void typeCode_isWrittenAsLabel() {
		String prompt = promptBuilder.build(ReasonPromptVariant.A, taste(), "PCLN", List.of(store(1L, "밀도", 0.71)),
			Map.of());

		assertThat(prompt).contains("- 유형: 뉴웨이브 미니멀");
	}
}
