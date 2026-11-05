package sweetgirl.croissant.follow.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.alarm.service.CommunityNotificationService;
import sweetgirl.croissant.follow.code.FollowErrorCode;
import sweetgirl.croissant.follow.exception.FollowException;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.follow.repository.MemberFollowRequestRepository;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.SocialMemberReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("FollowRequestCommandService")
class FollowRequestCommandServiceTest {

	private static final Long ME = 1L;
	private static final Long REQUESTER = 2L;

	@Mock
	private MemberFollowRepository memberFollowRepository;
	@Mock
	private MemberFollowRequestRepository memberFollowRequestRepository;
	@Mock
	private MemberReader memberReader;
	@Mock
	private SocialMemberReader socialMemberReader;
	@Mock
	private CommunityNotificationService communityNotificationService;

	@InjectMocks
	private FollowRequestCommandService service;

	private Member member(Long id) {
		Member member = Member.builder().nickname("회원" + id).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	@Nested
	@DisplayName("accept")
	class Accept {

		@Test
		@DisplayName("요청이 있으면 요청을 지우고 팔로우를 넣고 수락 알림을 보내고, 내 알림함의 요청 알림을 거둔다")
		void accepts() {
			Member requester = member(REQUESTER);
			given(memberFollowRequestRepository.existsByIdRequesterIdAndIdTargetId(REQUESTER, ME)).willReturn(true);
			given(socialMemberReader.findSocial(REQUESTER)).willReturn(Optional.of(requester));
			given(memberFollowRepository.insertIfAbsent(eq(REQUESTER), eq(ME), any())).willReturn(1);
			given(memberReader.getMember(ME)).willReturn(member(ME));

			service.accept(ME, REQUESTER);

			then(memberFollowRequestRepository).should().deleteByPair(REQUESTER, ME);
			then(communityNotificationService).should().notifyFollowAccepted(any(), eq(requester));
			// 내 알림함의 "요청했어요"는 거둔다 — 남으면 눌러도 없는 요청이고 재요청 때 옛 시각으로 보인다
			then(communityNotificationService).should().retractFollowRequestNotification(REQUESTER, ME);
		}

		@Test
		@DisplayName("이미 팔로우 중이었으면(insert 0) 수락 알림은 보내지 않는다 — 요청 알림은 거둔다")
		void alreadyFollowing() {
			given(memberFollowRequestRepository.existsByIdRequesterIdAndIdTargetId(REQUESTER, ME)).willReturn(true);
			given(socialMemberReader.findSocial(REQUESTER)).willReturn(Optional.of(member(REQUESTER)));
			given(memberFollowRepository.insertIfAbsent(eq(REQUESTER), eq(ME), any())).willReturn(0);

			service.accept(ME, REQUESTER);

			then(communityNotificationService).should(never()).notifyFollowAccepted(any(), any());
			then(communityNotificationService).should().retractFollowRequestNotification(REQUESTER, ME);
		}

		@Test
		@DisplayName("요청이 없으면 FOLLOW404_REQUEST")
		void notFound() {
			given(memberFollowRequestRepository.existsByIdRequesterIdAndIdTargetId(REQUESTER, ME)).willReturn(false);

			assertThatThrownBy(() -> service.accept(ME, REQUESTER))
				.isInstanceOf(FollowException.class)
				.extracting("errorCode").isEqualTo(FollowErrorCode.FOLLOW_REQUEST_NOT_FOUND);
			then(memberFollowRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("요청한 사람이 그새 탈퇴했으면 요청만 지우고 FOLLOW404_REQUEST")
		void requesterWithdrawn() {
			given(memberFollowRequestRepository.existsByIdRequesterIdAndIdTargetId(REQUESTER, ME)).willReturn(true);
			given(socialMemberReader.findSocial(REQUESTER)).willReturn(Optional.empty());

			assertThatThrownBy(() -> service.accept(ME, REQUESTER))
				.isInstanceOf(FollowException.class)
				.extracting("errorCode").isEqualTo(FollowErrorCode.FOLLOW_REQUEST_NOT_FOUND);
			then(memberFollowRequestRepository).should().deleteByPair(REQUESTER, ME);
			then(communityNotificationService).should().retractFollowRequestNotification(REQUESTER, ME);
			then(memberFollowRepository).should(never()).insertIfAbsent(anyLong(), anyLong(), any());
		}

		@Test
		@DisplayName("게스트는 MEMBER403_GUEST")
		void guest() {
			willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED))
				.given(memberReader).requireLoggedIn(ME);

			assertThatThrownBy(() -> service.accept(ME, REQUESTER)).isInstanceOf(MemberException.class);
			then(memberFollowRequestRepository).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("reject")
	class Reject {

		@Test
		@DisplayName("요청을 지우고 내 알림함의 요청 알림을 거둔다 — 요청한 사람에게는 알리지 않는다")
		void rejects() {
			given(memberFollowRequestRepository.deleteByPair(REQUESTER, ME)).willReturn(1);

			service.reject(ME, REQUESTER);

			then(communityNotificationService).should().retractFollowRequestNotification(REQUESTER, ME);
			then(communityNotificationService).should(never()).notifyFollowAccepted(any(), any());
			then(memberFollowRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("요청이 없으면 FOLLOW404_REQUEST")
		void notFound() {
			given(memberFollowRequestRepository.deleteByPair(REQUESTER, ME)).willReturn(0);

			assertThatThrownBy(() -> service.reject(ME, REQUESTER))
				.isInstanceOf(FollowException.class)
				.extracting("errorCode").isEqualTo(FollowErrorCode.FOLLOW_REQUEST_NOT_FOUND);
			then(communityNotificationService).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("acceptAllOf")
	class AcceptAllOf {

		@Test
		@DisplayName("받은 요청을 전부 팔로우로 바꾸고 요청을 비운다 — 수락 알림은 없고 요청 알림은 거둔다")
		void acceptsAll() {
			given(memberFollowRequestRepository.findRequesterIdsByTargetId(ME)).willReturn(List.of(2L, 3L));

			service.acceptAllOf(ME);

			then(memberFollowRepository).should().insertIfAbsent(eq(2L), eq(ME), any());
			then(memberFollowRepository).should().insertIfAbsent(eq(3L), eq(ME), any());
			then(memberFollowRequestRepository).should().deleteAllByTargetId(ME);
			then(communityNotificationService).should(never()).notifyFollowAccepted(any(), any());
			// 자동 수락도 알림함의 "요청했어요"는 거둔다 — 안 거두면 재요청이 유니크 제약에 걸려 옛 시각으로 보인다
			then(communityNotificationService).should().retractFollowRequestNotification(2L, ME);
			then(communityNotificationService).should().retractFollowRequestNotification(3L, ME);
		}

		@Test
		@DisplayName("받은 요청이 없으면 아무것도 안 한다")
		void nothing() {
			given(memberFollowRequestRepository.findRequesterIdsByTargetId(ME)).willReturn(List.of());

			service.acceptAllOf(ME);

			then(memberFollowRepository).shouldHaveNoInteractions();
			then(memberFollowRequestRepository).should(never()).deleteAllByTargetId(anyLong());
		}
	}
}
