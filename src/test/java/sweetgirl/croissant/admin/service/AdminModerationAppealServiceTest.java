package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.abuse.entity.ModerationAppeal;
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.enums.ModerationAppealStatus;
import sweetgirl.croissant.abuse.enums.ModerationTargetType;
import sweetgirl.croissant.abuse.repository.ModerationAppealRepository;
import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.dto.request.AdminModerationAppealReviewRequest;
import sweetgirl.croissant.admin.dto.response.AdminModerationAppealResponse;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.MemberRestriction;
import sweetgirl.croissant.member.enums.MemberRestrictionType;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.repository.ReviewRepository;

@ExtendWith(MockitoExtension.class)
class AdminModerationAppealServiceTest {

	@Mock
	ModerationAppealRepository moderationAppealRepository;
	@Mock
	ReviewRepository reviewRepository;
	@Mock
	MemberRestrictionRepository memberRestrictionRepository;

	@InjectMocks
	AdminModerationAppealService adminModerationAppealService;

	@Test
	void getAppeals_filtersByStatus() {
		ModerationAppeal appeal = appeal(1L,
			moderationAction(55L, member(20L), ModerationTargetType.REVIEW, 123L, AbuseReportAction.HIDE_REVIEW));
		given(moderationAppealRepository.findByStatus(
			ModerationAppealStatus.PENDING,
			PageRequest.of(0, 20)))
			.willReturn(new PageImpl<>(List.of(appeal)));

		Page<AdminModerationAppealResponse> response = adminModerationAppealService.getAppeals(
			"PENDING",
			0,
			20);

		assertThat(response.getContent()).hasSize(1);
		assertThat(response.getContent().get(0).appealId()).isEqualTo(1L);
		assertThat(response.getContent().get(0).status()).isEqualTo("PENDING");
	}

	@Test
	void reviewAppeal_acceptDeleteReview_restoresReview() {
		Member member = member(20L);
		ModerationAction action = moderationAction(55L, member, ModerationTargetType.REVIEW, 123L,
			AbuseReportAction.DELETE_REVIEW);
		ModerationAppeal appeal = appeal(1L, action);
		Review review = mock(Review.class);
		given(moderationAppealRepository.findWithDetailsById(1L)).willReturn(Optional.of(appeal));
		given(reviewRepository.findByIdWithDetails(123L)).willReturn(Optional.of(review));

		AdminModerationAppealResponse response = adminModerationAppealService.reviewAppeal(
			1L,
			new AdminModerationAppealReviewRequest("ACCEPT", " 복구 처리 "));

		verify(review).restoreModeration();
		assertThat(response.status()).isEqualTo("ACCEPTED");
		assertThat(response.adminMemo()).isEqualTo("복구 처리");
		assertThat(appeal.getStatus()).isEqualTo(ModerationAppealStatus.ACCEPTED);
	}

	@Test
	void reviewAppeal_acceptRestriction_releasesRestriction() {
		Member member = member(20L);
		MemberRestriction restriction = new MemberRestriction(
			member,
			MemberRestrictionType.WRITE_RESTRICTED,
			"반복 위반",
			LocalDateTime.now().minusDays(1),
			LocalDateTime.now().plusDays(7));
		ReflectionTestUtils.setField(restriction, "id", 200L);
		ModerationAction action = moderationAction(55L, member, ModerationTargetType.MEMBER_RESTRICTION, 200L,
			AbuseReportAction.RESTRICT_MEMBER);
		ModerationAppeal appeal = appeal(1L, action);
		given(moderationAppealRepository.findWithDetailsById(1L)).willReturn(Optional.of(appeal));
		given(memberRestrictionRepository.findById(200L)).willReturn(Optional.of(restriction));

		AdminModerationAppealResponse response = adminModerationAppealService.reviewAppeal(
			1L,
			new AdminModerationAppealReviewRequest("ACCEPT", "제한 해제"));

		assertThat(response.status()).isEqualTo("ACCEPTED");
		assertThat(restriction.getEndsAt()).isBeforeOrEqualTo(LocalDateTime.now());
	}

	@Test
	void reviewAppeal_rejectsAlreadyReviewedAppeal() {
		ModerationAppeal appeal = appeal(1L,
			moderationAction(55L, member(20L), ModerationTargetType.REVIEW, 123L, AbuseReportAction.HIDE_REVIEW));
		appeal.reject("이미 반려");
		given(moderationAppealRepository.findWithDetailsById(1L)).willReturn(Optional.of(appeal));

		assertThatThrownBy(() -> adminModerationAppealService.reviewAppeal(
			1L,
			new AdminModerationAppealReviewRequest("ACCEPT", "복구")))
			.isInstanceOf(AdminException.class)
			.hasMessageContaining(AdminErrorCode.APPEAL_ALREADY_REVIEWED.getMessage());
	}

	private ModerationAppeal appeal(Long id, ModerationAction action) {
		ModerationAppeal appeal = ModerationAppeal.builder()
			.moderationAction(action)
			.member(action.getMember())
			.content("이의제기합니다.")
			.build();
		ReflectionTestUtils.setField(appeal, "id", id);
		ReflectionTestUtils.setField(appeal, "createdAt", LocalDateTime.of(2026, 6, 5, 10, 0));
		return appeal;
	}

	private ModerationAction moderationAction(
			Long id,
			Member member,
			ModerationTargetType targetType,
			Long targetId,
			AbuseReportAction action) {
		ModerationAction moderationAction = ModerationAction.builder()
			.targetType(targetType)
			.targetId(targetId)
			.member(member)
			.action(action)
			.reason("운영 정책 위반")
			.build();
		ReflectionTestUtils.setField(moderationAction, "id", id);
		return moderationAction;
	}

	private Member member(Long id) {
		Member member = Member.builder()
			.nickname("사용자")
			.build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}
}
