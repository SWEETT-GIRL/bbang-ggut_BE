package sweetgirl.croissant.recommendation.service;

import java.util.ArrayList;
import java.util.List;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.TasteQuery;

/**
 * 코스 한 벌의 이유 문장을 만든다 (설계 §7).
 *
 * <p><b>운영과 실험이 같은 함수를 쓴다.</b> 가게마다 따로 만들면 코스 안에서 무엇이 이미 나왔는지 알 수 없고,
 * 실험이 가게 단위로 재면 표현 변형이 실험에 안 잡혀 <b>실제 출력과 다른 조건으로 측정</b>하게 된다.
 *
 * <p><b>출력 계약:</b> 입력 가게마다 결과가 정확히 하나이고 순서가 유지된다. 근거가 있어도 쓸 문장이 없을 수 있어
 * (전부 미지원 태그이거나 길이 초과) 그때도 기본 문장과 {@link ReasonEvidence#none()} 을 돌려준다.
 * 추적은 <b>최종 출력 기준</b>이다 — 처음 찾은 근거를 그대로 들고 있으면, 실제로는 기본 문장이 나갔는데
 * 개인화 근거 사용률에 잡힌다.
 *
 * <p>{@code fallback} (취향 후보 3곳 미만으로 취향 항을 빼고 다시 조회한 경우)이어도 <b>확인되는 종류 일치는
 * 설명한다.</b> 다만 "이 취향 때문에 골랐다" 는 인과 표현을 쓰지 않는데, 그것은 템플릿이 애초에 조건형과
 * 특징형뿐이라 자연히 지켜진다 — 이 플래그로 근거를 깎지 않는 이유다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ReasonSentenceGenerator {

	public static List<GeneratedReasonSentence> generate(TasteQuery taste, List<CandidateStoreRow> ordered,
			RecommendationProperties.Score score) {
		List<GeneratedReasonSentence> chosen = new ArrayList<>(ordered.size());
		for (CandidateStoreRow row : ordered) {
			chosen.add(choose(taste, row, score, chosen));
		}
		return List.copyOf(chosen);
	}

	/**
	 * 정렬된 근거 중 <b>쓸 문장이 있는 첫 근거</b>를 고른다. 그 안에서 앞 문장과 겹치지 않는 표현을 먼저 보고,
	 * 없으면 반복을 허용한다 — 근거를 깎느니 같은 문장을 두 번 쓰는 게 낫다(설계 §7.1).
	 *
	 * <p>두 경우를 가른다. <b>문장을 못 만드는 근거</b>(미지원 태그 · 재료 부족 · 전부 길이 초과)는 다음 근거로
	 * 넘어가고, <b>앞 문장과 같은 경우</b>는 같은 근거의 다른 표현을 본다. 앞의 것은 근거가 무효라서이고
	 * 뒤의 것은 말만 겹친 것이라, 섞으면 멀쩡한 개인화 근거를 중복 때문에 버리게 된다.
	 */
	private static GeneratedReasonSentence choose(TasteQuery taste, CandidateStoreRow row,
			RecommendationProperties.Score score, List<GeneratedReasonSentence> already) {
		GeneratedReasonSentence firstUsable = null;
		for (ReasonEvidence evidence : ReasonEvidenceResolver.resolve(taste, row, score)) {
			List<GeneratedReasonSentence> candidates = ReasonTemplates.candidates(evidence, row.storeId());
			if (candidates.isEmpty()) {
				continue;
			}
			if (firstUsable == null) {
				firstUsable = candidates.get(0);
			}
			for (GeneratedReasonSentence candidate : candidates) {
				if (notUsedYet(candidate, already)) {
					return candidate;
				}
			}
		}
		// 어느 근거로도 새 문장을 못 만들었다 — 근거가 있었다면 그 문장을 반복해서라도 쓴다
		return firstUsable != null ? firstUsable : ReasonTemplates.fallback(row.storeId());
	}

	private static boolean notUsedYet(GeneratedReasonSentence candidate, List<GeneratedReasonSentence> already) {
		return already.stream().noneMatch(used -> used.text().equals(candidate.text()));
	}
}
