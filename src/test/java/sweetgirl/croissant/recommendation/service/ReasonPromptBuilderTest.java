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
			List.of(store(1L, "밀도", 0.71), store(2L, "베통", 0.12)));

		assertThat(prompt).doesNotContain("선정 근거");
		assertThat(prompt).contains("밀도", "베통", "storeId=1", "storeId=2");
	}

	@Test
	@DisplayName("D 는 가게마다 룰의 선정 근거를 붙인다 — 겹치는 것만 적는다")
	void buildD_hasRuleEvidenceWithOverlapOnly() {
		String prompt = promptBuilder.build(ReasonPromptVariant.D, taste(), "PCLN", List.of(store(1L, "밀도", 0.71)));

		assertThat(prompt).contains("선정 근거: 취향 일치도 0.71");
		// 회원 태그 셋 중 가게와 겹치는 것은 동네빵집 하나뿐이다
		assertThat(prompt).contains("겹치는 태그 동네빵집");
		assertThat(prompt).doesNotContain("겹치는 태그 동네빵집, 아침");
		assertThat(prompt).contains("겹치는 빵 종류 소금빵");
	}

	@Test
	@DisplayName("약한 축은 극을 말하지 않는다 — 0 근처를 근거로 쓰면 지어내기가 된다")
	void weakAxis_isWrittenAsMiddle() {
		String prompt = promptBuilder.build(ReasonPromptVariant.A, taste(), "PCLN", List.of(store(1L, "밀도", 0.71)));

		assertThat(prompt).contains("- 맛: +0.62(담백)");
		assertThat(prompt).contains("- 분위기: +0.08(중간)");
	}

	@Test
	@DisplayName("축이 없는 회원이면 축 줄이 없음이 된다")
	void noAxis_isWrittenAsNone() {
		String prompt = promptBuilder.build(ReasonPromptVariant.A, TasteQuery.empty(), null,
			List.of(store(1L, "밀도", 0)));

		assertThat(prompt).contains("- 맛: 없음");
		assertThat(prompt).contains("- 좋아하는 태그: 없음");
		assertThat(prompt).contains("- 자주 먹는 빵 종류: 없음");
		assertThat(prompt).doesNotContain("- 유형:");
	}

	@Test
	@DisplayName("가게 수와 임계값이 프롬프트에 그대로 박힌다")
	void placeholders_areReplaced() {
		String prompt = promptBuilder.build(ReasonPromptVariant.D, taste(), "PCLN",
			List.of(store(1L, "밀도", 0.71), store(2L, "베통", 0.12)));

		assertThat(prompt).contains("[추천된 빵집 2곳]");
		assertThat(prompt).contains("0.20 를 넘으면");
		assertThat(prompt).doesNotContain("{storeCount}", "{taste}", "{stores}", "{tasteThreshold}");
	}

	@Test
	@DisplayName("유형 코드는 화면 이름으로 바뀐다")
	void typeCode_isWrittenAsLabel() {
		String prompt = promptBuilder.build(ReasonPromptVariant.A, taste(), "PCLN", List.of(store(1L, "밀도", 0.71)));

		assertThat(prompt).contains("- 유형: 뉴웨이브 미니멀");
	}
}
