package sweetgirl.croissant.recommendation.dto.response;

import java.util.List;

/**
 * 이유 문장 실험 결과 (#662). 두 모드 중 하나만 채워진다.
 *
 * @param runs 계측 모드 — 조합별 지연·토큰·문장을 다 보여준다
 * @param blindEntries 블라인드 모드 — 라벨만 붙은 문장이 섞여 있다
 * @param blindAnswerKey 라벨 → 조합 표를 Base64 로 접어 둔 것. 암호가 아니라 <b>눈가림</b>이다 — 문장을 고르기
 *        전에 답이 눈에 먼저 들어오지 않게 하려는 것뿐이고, 풀어 보면 그대로 나온다
 */
public record ReasonExperimentResponse(
		ReasonExperimentInput input,
		List<ReasonExperimentRun> runs,
		List<BlindReasonEntry> blindEntries,
		String blindAnswerKey) {

	public static ReasonExperimentResponse measured(ReasonExperimentInput input, List<ReasonExperimentRun> runs) {
		return new ReasonExperimentResponse(input, runs, null, null);
	}

	public static ReasonExperimentResponse blind(ReasonExperimentInput input, List<BlindReasonEntry> entries,
			String answerKey) {
		return new ReasonExperimentResponse(input, null, entries, answerKey);
	}
}
