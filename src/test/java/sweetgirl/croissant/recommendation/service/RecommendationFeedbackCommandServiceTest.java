package sweetgirl.croissant.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.dto.request.RecommendationFeedbackRequest;
import sweetgirl.croissant.recommendation.entity.Recommendation;
import sweetgirl.croissant.recommendation.entity.RecommendationFeedback;
import sweetgirl.croissant.recommendation.enums.FeedbackAction;
import sweetgirl.croissant.recommendation.enums.FeedbackReason;
import sweetgirl.croissant.recommendation.exception.RecommendationException;
import sweetgirl.croissant.recommendation.repository.RecommendationFeedbackRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationRepository;

@ExtendWith(MockitoExtension.class)
class RecommendationFeedbackCommandServiceTest {

	private static final Long MEMBER_ID = 7L;
	private static final Long RECOMMENDATION_ID = 99L;

	@Mock
	private RecommendationRepository recommendationRepository;
	@Mock
	private RecommendationFeedbackRepository recommendationFeedbackRepository;

	@InjectMocks
	private RecommendationFeedbackCommandService recommendationFeedbackCommandService;

	@Test
	@DisplayName("내 추천이면 반응이 그대로 저장된다 — 👎 와 이유 칩")
	void record_savesDislikeWithReason() {
		given(recommendationRepository.findByIdAndMemberId(RECOMMENDATION_ID, MEMBER_ID))
			.willReturn(Optional.of(mock(Recommendation.class)));

		recommendationFeedbackCommandService.record(MEMBER_ID, RECOMMENDATION_ID,
			new RecommendationFeedbackRequest(FeedbackAction.DISLIKE, null, FeedbackReason.TOO_FAR));

		ArgumentCaptor<RecommendationFeedback> saved = ArgumentCaptor.forClass(RecommendationFeedback.class);
		verify(recommendationFeedbackRepository).save(saved.capture());
		assertThat(saved.getValue().getRecommendationId()).isEqualTo(RECOMMENDATION_ID);
		assertThat(saved.getValue().getAction()).isEqualTo(FeedbackAction.DISLIKE);
		assertThat(saved.getValue().getReason()).isEqualTo(FeedbackReason.TOO_FAR);
	}

	@Test
	@DisplayName("남의 추천이거나 없는 추천이면 404 이고 아무것도 저장하지 않는다")
	void record_unknownRecommendation_isNotFound() {
		given(recommendationRepository.findByIdAndMemberId(RECOMMENDATION_ID, MEMBER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> recommendationFeedbackCommandService.record(MEMBER_ID, RECOMMENDATION_ID,
			new RecommendationFeedbackRequest(FeedbackAction.VIEW, null, null)))
			.isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND));
		verify(recommendationFeedbackRepository, never()).save(any());
	}

	@Test
	@DisplayName("가게 단위 반응인데 가게 ID 가 없으면 엔티티 규칙대로 400 이다")
	void record_storeScopedWithoutStoreId_isRejected() {
		given(recommendationRepository.findByIdAndMemberId(RECOMMENDATION_ID, MEMBER_ID))
			.willReturn(Optional.of(mock(Recommendation.class)));

		assertThatThrownBy(() -> recommendationFeedbackCommandService.record(MEMBER_ID, RECOMMENDATION_ID,
			new RecommendationFeedbackRequest(FeedbackAction.STORE_TAP, null, null)))
			.isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.FEEDBACK_STORE_REQUIRED));
		verify(recommendationFeedbackRepository, never()).save(any());
	}
}
