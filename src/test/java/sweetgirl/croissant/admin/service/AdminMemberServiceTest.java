package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.dto.request.AdminMemberRestrictionRequest;
import sweetgirl.croissant.admin.dto.request.AdminProfileResetRequest;
import sweetgirl.croissant.admin.dto.response.AdminMemberRestrictionResponse;
import sweetgirl.croissant.admin.dto.response.AdminProfileResetResponse;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.MemberRestriction;
import sweetgirl.croissant.member.enums.MemberRestrictionType;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.report.entity.ModerationAction;
import sweetgirl.croissant.report.enums.ModerationTargetType;
import sweetgirl.croissant.report.enums.UserReportAction;
import sweetgirl.croissant.report.repository.ModerationActionRepository;

@ExtendWith(MockitoExtension.class)
class AdminMemberServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	MemberRestrictionRepository memberRestrictionRepository;
	@Mock
	ModerationActionRepository moderationActionRepository;

	@InjectMocks
	AdminMemberService adminMemberService;

	@Test
	@DisplayName("resetProfile: 회원 닉네임을 기본값으로 초기화하고 이력을 저장한다")
	void resetProfile_resetsNicknameAndRecordsAction() {
		Member member = member(20L, "부적절닉네임");
		given(memberReader.getMember(20L)).willReturn(member);
		given(moderationActionRepository.save(any(ModerationAction.class)))
			.willAnswer(invocation -> {
				ModerationAction action = invocation.getArgument(0);
				ReflectionTestUtils.setField(action, "id", 100L);
				return action;
			});

		AdminProfileResetResponse response = adminMemberService.resetProfile(
			20L,
			new AdminProfileResetRequest(" 부적절한 닉네임 "));

		assertThat(response.memberId()).isEqualTo(20L);
		assertThat(response.nickname()).isEqualTo("빵긋 사용자");
		assertThat(member.getNickname()).isEqualTo("빵긋 사용자");
		verify(moderationActionRepository)
			.save(argThat(action -> action.getTargetType() == ModerationTargetType.MEMBER_PROFILE
				&& action.getTargetId().equals(20L)
				&& action.getMember() == member
				&& action.getAction() == UserReportAction.RESET_PROFILE
				&& action.getReason().equals("부적절한 닉네임")));
	}

	@Test
	@DisplayName("createRestriction: 회원 제한을 시작/종료 시각과 함께 저장한다")
	void createRestriction_savesRestrictionWithDuration() {
		Member member = member(20L, "사용자");
		given(memberReader.getMember(20L)).willReturn(member);
		given(memberRestrictionRepository.save(any(MemberRestriction.class)))
			.willAnswer(invocation -> {
				MemberRestriction restriction = invocation.getArgument(0);
				ReflectionTestUtils.setField(restriction, "id", 200L);
				return restriction;
			});
		given(moderationActionRepository.save(any(ModerationAction.class)))
			.willAnswer(invocation -> {
				ModerationAction action = invocation.getArgument(0);
				ReflectionTestUtils.setField(action, "id", 300L);
				return action;
			});

		LocalDateTime before = LocalDateTime.now();
		AdminMemberRestrictionResponse response = adminMemberService.createRestriction(
			20L,
			new AdminMemberRestrictionRequest("WRITE_RESTRICTED", 7, " 반복 위반 "));
		LocalDateTime after = LocalDateTime.now();

		assertThat(response.restrictionId()).isEqualTo(200L);
		assertThat(response.actionId()).isEqualTo(300L);
		assertThat(response.memberId()).isEqualTo(20L);
		assertThat(response.type()).isEqualTo("WRITE_RESTRICTED");
		assertThat(response.reason()).isEqualTo("반복 위반");

		ArgumentCaptor<MemberRestriction> captor = ArgumentCaptor.forClass(MemberRestriction.class);
		verify(memberRestrictionRepository).save(captor.capture());
		MemberRestriction saved = captor.getValue();
		assertThat(saved.getRestrictionType()).isEqualTo(MemberRestrictionType.WRITE_RESTRICTED);
		assertThat(saved.getStartsAt()).isBetween(before, after);
		assertThat(saved.getEndsAt()).isBetween(before.plusDays(7), after.plusDays(7));
		verify(moderationActionRepository)
			.save(argThat(action -> action.getTargetType() == ModerationTargetType.MEMBER_RESTRICTION
				&& action.getTargetId().equals(200L)
				&& action.getMember() == member
				&& action.getAction() == UserReportAction.RESTRICT_MEMBER
				&& action.getReason().equals("반복 위반")));
	}

	@Test
	@DisplayName("createRestriction: 잘못된 제한 타입이면 예외를 던진다")
	void createRestriction_invalidType_throws() {
		given(memberReader.getMember(20L)).willReturn(member(20L, "사용자"));

		assertThatThrownBy(() -> adminMemberService.createRestriction(
			20L,
			new AdminMemberRestrictionRequest("UNKNOWN", 7, "사유")))
			.isInstanceOf(AdminException.class)
			.hasMessageContaining(AdminErrorCode.MEMBER_RESTRICTION_INVALID_TYPE.getMessage());
	}

	private Member member(Long id, String nickname) {
		Member member = Member.builder()
			.nickname(nickname)
			.build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}
}
