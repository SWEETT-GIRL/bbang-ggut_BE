package sweetgirl.croissant.pat.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.alarm.service.CommunityNotificationService;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.member.service.SocialMemberReader;
import sweetgirl.croissant.pat.code.PatErrorCode;
import sweetgirl.croissant.pat.exception.PatException;
import sweetgirl.croissant.pat.repository.MemberPatRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("PatCommandService")
class PatCommandServiceTest {

	private static final Long ME = 1L;
	private static final Long TARGET = 2L;

	@Mock
	private MemberPatRepository memberPatRepository;
	@Mock
	private MemberBlockRepository memberBlockRepository;
	@Mock
	private MemberReader memberReader;
	@Mock
	private MemberRestrictionReader memberRestrictionReader;
	@Mock
	private SocialMemberReader socialMemberReader;
	@Mock
	private CommunityNotificationService communityNotificationService;

	@InjectMocks
	private PatCommandService patCommandService;

	private Member member(Long id) {
		Member member = Member.builder().nickname("회원" + id).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	@Test
	@DisplayName("처음 치면 저장하고 알린다")
	void pat() {
		Member target = member(TARGET);
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.of(target));
		given(memberBlockRepository.existsBetween(ME, TARGET)).willReturn(false);
		given(memberPatRepository.insertIfAbsent(eq(ME), eq(TARGET), any(), any())).willReturn(1);
		Member me = member(ME);
		given(memberReader.getMember(ME)).willReturn(me);

		patCommandService.pat(ME, TARGET);

		verify(memberReader).requireLoggedIn(ME);
		verify(memberRestrictionReader).validateCanWrite(ME);
		verify(communityNotificationService).notifyPat(me, target);
	}

	@Test
	@DisplayName("오늘 이미 쳤으면 아무것도 안 하고 조용히 끝난다")
	void alreadyToday() {
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.of(member(TARGET)));
		given(memberPatRepository.insertIfAbsent(eq(ME), eq(TARGET), any(), any())).willReturn(0);

		patCommandService.pat(ME, TARGET);

		verify(communityNotificationService, never()).notifyPat(any(), any());
	}

	@Test
	@DisplayName("나 자신은 400")
	void self() {
		assertThatThrownBy(() -> patCommandService.pat(ME, ME))
			.isInstanceOf(PatException.class)
			.hasMessage(PatErrorCode.PAT_SELF.getMessage());
		verify(memberPatRepository, never()).insertIfAbsent(anyLong(), anyLong(), any(), any());
	}

	@Test
	@DisplayName("상대가 없거나 게스트 · 봇 · 탈퇴면 404")
	void targetNotFound() {
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.empty());

		assertThatThrownBy(() -> patCommandService.pat(ME, TARGET))
			.isInstanceOf(PatException.class)
			.hasMessage(PatErrorCode.PAT_TARGET_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("차단 관계면 403")
	void blocked() {
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.of(member(TARGET)));
		given(memberBlockRepository.existsBetween(ME, TARGET)).willReturn(true);

		assertThatThrownBy(() -> patCommandService.pat(ME, TARGET))
			.isInstanceOf(PatException.class)
			.hasMessage(PatErrorCode.PAT_BLOCKED.getMessage());
	}

	@Test
	@DisplayName("작성 제한 · 정지 중이면 403 — 팔로우와 달리 톡은 남에게 닿는다")
	void restricted() {
		willThrow(new MemberException(MemberErrorCode.MEMBER_WRITE_RESTRICTED))
			.given(memberRestrictionReader).validateCanWrite(ME);

		assertThatThrownBy(() -> patCommandService.pat(ME, TARGET))
			.isInstanceOf(MemberException.class);
		verify(memberPatRepository, never()).insertIfAbsent(anyLong(), anyLong(), any(), any());
	}
}
