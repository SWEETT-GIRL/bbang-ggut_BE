package sweetgirl.croissant.abuse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.abuse.code.AbuseReportErrorCode;
import sweetgirl.croissant.abuse.dto.request.ModerationAppealCreateRequest;
import sweetgirl.croissant.abuse.dto.response.ModerationAppealCreateResponse;
import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.abuse.entity.ModerationAppeal;
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.enums.ModerationAppealStatus;
import sweetgirl.croissant.abuse.enums.ModerationTargetType;
import sweetgirl.croissant.abuse.exception.AbuseReportException;
import sweetgirl.croissant.abuse.repository.ModerationActionRepository;
import sweetgirl.croissant.abuse.repository.ModerationAppealRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;

@ExtendWith(MockitoExtension.class)
class ModerationAppealCommandServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	ModerationActionRepository moderationActionRepository;
	@Mock
	ModerationAppealRepository moderationAppealRepository;

	@InjectMocks
	ModerationAppealCommandService moderationAppealCommandService;

	@Test
	void createAppeal_savesAppeal() {
		Member member = member(20L);
		ModerationAction action = moderationAction(55L, member, AbuseReportAction.DELETE_REVIEW,
			LocalDateTime.now().minusDays(3));
		given(memberReader.getMember(20L)).willReturn(member);
		given(moderationActionRepository.findByIdWithMemberAndReport(55L)).willReturn(Optional.of(action));
		given(moderationAppealRepository.existsByModerationActionIdAndMemberId(55L, 20L)).willReturn(false);
		given(moderationAppealRepository.save(any(ModerationAppeal.class))).willAnswer(invocation -> {
			ModerationAppeal appeal = invocation.getArgument(0);
			ReflectionTestUtils.setField(appeal, "id", 100L);
			ReflectionTestUtils.setField(appeal, "createdAt", LocalDateTime.of(2026, 6, 5, 10, 0));
			return appeal;
		});

		ModerationAppealCreateResponse response = moderationAppealCommandService.createAppeal(
			20L,
			new ModerationAppealCreateRequest(55L, " 해당 리뷰는 욕설이 아닙니다. "));

		assertThat(response.appealId()).isEqualTo(100L);
		assertThat(response.actionId()).isEqualTo(55L);
		assertThat(response.status()).isEqualTo(ModerationAppealStatus.PENDING);

		ArgumentCaptor<ModerationAppeal> captor = ArgumentCaptor.forClass(ModerationAppeal.class);
		verify(moderationAppealRepository).save(captor.capture());
		assertThat(captor.getValue().getModerationAction()).isEqualTo(action);
		assertThat(captor.getValue().getMember()).isEqualTo(member);
		assertThat(captor.getValue().getContent()).isEqualTo("해당 리뷰는 욕설이 아닙니다.");
	}

	@Test
	void createAppeal_rejectsOtherMemberAction() {
		Member member = member(20L);
		ModerationAction action = moderationAction(55L, member(21L), AbuseReportAction.HIDE_REVIEW,
			LocalDateTime.now());
		given(memberReader.getMember(20L)).willReturn(member);
		given(moderationActionRepository.findByIdWithMemberAndReport(55L)).willReturn(Optional.of(action));

		assertThatThrownBy(() -> moderationAppealCommandService.createAppeal(
			20L,
			new ModerationAppealCreateRequest(55L, "이의제기합니다.")))
			.isInstanceOf(AbuseReportException.class)
			.hasMessageContaining(AbuseReportErrorCode.APPEAL_FORBIDDEN.getMessage());

		verify(moderationAppealRepository, never()).save(any());
	}

	@Test
	void createAppeal_rejectsExpiredAction() {
		Member member = member(20L);
		ModerationAction action = moderationAction(55L, member, AbuseReportAction.DELETE_REVIEW,
			LocalDateTime.now().minusDays(31));
		given(memberReader.getMember(20L)).willReturn(member);
		given(moderationActionRepository.findByIdWithMemberAndReport(55L)).willReturn(Optional.of(action));

		assertThatThrownBy(() -> moderationAppealCommandService.createAppeal(
			20L,
			new ModerationAppealCreateRequest(55L, "이의제기합니다.")))
			.isInstanceOf(AbuseReportException.class)
			.hasMessageContaining(AbuseReportErrorCode.APPEAL_PERIOD_EXPIRED.getMessage());

		verify(moderationAppealRepository, never()).save(any());
	}

	@Test
	void createAppeal_rejectsDuplicateAppeal() {
		Member member = member(20L);
		ModerationAction action = moderationAction(55L, member, AbuseReportAction.RESET_PROFILE,
			LocalDateTime.now());
		given(memberReader.getMember(20L)).willReturn(member);
		given(moderationActionRepository.findByIdWithMemberAndReport(55L)).willReturn(Optional.of(action));
		given(moderationAppealRepository.existsByModerationActionIdAndMemberId(55L, 20L)).willReturn(true);

		assertThatThrownBy(() -> moderationAppealCommandService.createAppeal(
			20L,
			new ModerationAppealCreateRequest(55L, "이의제기합니다.")))
			.isInstanceOf(AbuseReportException.class)
			.hasMessageContaining(AbuseReportErrorCode.APPEAL_ALREADY_EXISTS.getMessage());

		verify(moderationAppealRepository, never()).save(any());
	}

	private ModerationAction moderationAction(
			Long id,
			Member member,
			AbuseReportAction action,
			LocalDateTime createdAt) {
		ModerationAction moderationAction = ModerationAction.builder()
			.targetType(ModerationTargetType.REVIEW)
			.targetId(123L)
			.member(member)
			.action(action)
			.reason("운영 정책 위반")
			.build();
		ReflectionTestUtils.setField(moderationAction, "id", id);
		ReflectionTestUtils.setField(moderationAction, "createdAt", createdAt);
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
