package sweetgirl.croissant.recommendation.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.dto.request.RecommendationFeedbackRequest;
import sweetgirl.croissant.recommendation.entity.Recommendation;
import sweetgirl.croissant.recommendation.entity.RecommendationFeedback;
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
		Recommendation recommendation = recommendationRepository.findByIdAndMemberId(recommendationId, memberId)
			.orElseThrow(() -> new RecommendationException(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND));
		// 결과에 없던 가게의 반응은 받지 않는다 — #663 이 이 행을 "보고 반응한 가게"로 믿는다
		if (request.storeId() != null && !recommendation.containsStore(request.storeId())) {
			throw new RecommendationException(RecommendationErrorCode.FEEDBACK_STORE_NOT_IN_RESULT);
		}
		recommendationFeedbackRepository.save(
			RecommendationFeedback.of(recommendationId, request.storeId(), request.action(), request.reason()));
	}
}
