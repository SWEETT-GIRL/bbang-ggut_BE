package sweetgirl.croissant.recommendation.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.dto.request.RecommendationFeedbackRequest;
import sweetgirl.croissant.recommendation.entity.Recommendation;
import sweetgirl.croissant.recommendation.entity.RecommendationFeedback;
import sweetgirl.croissant.recommendation.enums.FeedbackAction;
import sweetgirl.croissant.recommendation.exception.RecommendationException;
import sweetgirl.croissant.recommendation.repository.RecommendationFeedbackRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationRepository;

/** 추천 반응을 쌓는다 (설계 §3.1 · §6). 해석은 #663 배치의 몫이라 여기서는 사실만 남긴다. */
@Service
@RequiredArgsConstructor
@Transactional
public class RecommendationFeedbackCommandService {

	private final RecommendationRepository recommendationRepository;
	private final RecommendationFeedbackRepository recommendationFeedbackRepository;

	/** 남의 추천은 없는 것으로 본다 — 존재를 알리지 않는다. */
	public void record(Long memberId, Long recommendationId, RecommendationFeedbackRequest request) {
		if (request.action().isServerOnly()) {
			throw new RecommendationException(RecommendationErrorCode.FEEDBACK_ACTION_SERVER_ONLY);
		}
		Recommendation recommendation = recommendationRepository.findByIdAndMemberId(recommendationId, memberId)
			.orElseThrow(() -> new RecommendationException(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND));
		// 결과에 없던 가게의 반응은 받지 않는다 — #663 이 이 행을 "보고 반응한 가게"로 믿는다
		if (request.storeId() != null && !recommendation.containsStore(request.storeId())) {
			throw new RecommendationException(RecommendationErrorCode.FEEDBACK_STORE_NOT_IN_RESULT);
		}
		// VIEW 는 추천당 한 번 — 같은 세션에서 시트를 닫았다 열면 같은 묶음이 재사용되는데 앱은 재사용인지 모르고 매번 보낸다
		// (dev 실측 한 추천에 VIEW 4번). #663 이 "노출"을 셀 때 같은 묶음을 여러 번 세지 않게 서버가 거른다(FE 합의 09-15).
		// 확인-후-저장이라 동시 두 건(이중 마운트 · 재시도)은 둘 다 남을 수 있다 — 여기는 저장량을 줄이는 수준이고, 정확한 노출
		// 집계는 #663 이 추천 id 기준 DISTINCT 로 센다(PR #745 리뷰).
		if (request.action() == FeedbackAction.VIEW
			&& recommendationFeedbackRepository.existsByRecommendationIdAndAction(recommendationId,
				FeedbackAction.VIEW)) {
			return;
		}
		recommendationFeedbackRepository.save(
			RecommendationFeedback.of(recommendationId, request.storeId(), request.action(), request.reason()));
	}
}
