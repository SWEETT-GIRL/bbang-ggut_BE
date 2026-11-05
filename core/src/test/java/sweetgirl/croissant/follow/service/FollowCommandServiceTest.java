package sweetgirl.croissant.follow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.follow.code.FollowErrorCode;
import sweetgirl.croissant.follow.exception.FollowException;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.follow.repository.MemberFollowRequestRepository;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.member.service.SocialMemberReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("FollowCommandService")
class FollowCommandServiceTest {

	private static final Long ME = 1L;
	private static final Long TARGET = 2L;

	@Mock
	private MemberFollowRepository memberFollowRepository;
	@Mock
	private MemberFollowRequestRepository memberFollowRequestRepository;
	@Mock
	private MemberBlockRepository memberBlockRepository;
	@Mock
	private MemberReader memberReader;
	@Mock
	private MemberRestrictionReader memberRestrictionReader;
	@Mock
	private SocialMemberReader socialMemberReader;

	@Mock
	private sweetgirl.croissant.alarm.service.CommunityNotificationService communityNotificationService;

	@InjectMocks
	private FollowCommandService followCommandService;

	private Member member(Long id) {
		Member member = Member.builder().nickname("회원" + id).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private void givenSocialTarget() {
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.of(member(TARGET)));
	}

	@Nested
	@DisplayName("follow")
	class Follow {

		@Test
		@DisplayName("상대가 있고 차단 관계가 아니면 한 줄 넣는다 — FOLLOWING")
		void follows() {
			givenSocialTarget();
			given(memberBlockRepository.existsBetween(ME, TARGET)).willReturn(false);
			given(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(ME, TARGET)).willReturn(false);
			given(memberFollowRepository.countByIdFollowerIdAndCreatedAtGreaterThanEqual(eq(ME), any())).willReturn(3L);

			assertThat(followCommandService.follow(ME, TARGET)).isEqualTo(FollowResult.FOLLOWING);

			then(memberFollowRepository).should().insertIfAbsent(eq(ME), eq(TARGET), any());
			then(memberFollowRequestRepository).should(never()).insertIfAbsent(anyLong(), anyLong(), any());
		}

		@Test
		@DisplayName("상대가 비공개 계정이면 팔로우 대신 요청을 남기고 요청 알림을 보낸다 — REQUESTED")
		void privateTargetLeavesRequest() {
			Member target = member(TARGET);
			target.changePrivacy(true);
			given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.of(target));
			given(memberBlockRepository.existsBetween(ME, TARGET)).willReturn(false);
			given(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(ME, TARGET)).willReturn(false);
			given(memberFollowRequestRepository.existsByIdRequesterIdAndIdTargetId(ME, TARGET)).willReturn(false);
			given(memberFollowRepository.countByIdFollowerIdAndCreatedAtGreaterThanEqual(eq(ME), any())).willReturn(0L);
			given(memberFollowRequestRepository.insertIfAbsent(eq(ME), eq(TARGET), any())).willReturn(1);
			given(memberReader.getMember(ME)).willReturn(member(ME));

			assertThat(followCommandService.follow(ME, TARGET)).isEqualTo(FollowResult.REQUESTED);

			then(memberFollowRepository).should(never()).insertIfAbsent(anyLong(), anyLong(), any());
			then(communityNotificationService).should().notifyFollowRequest(any(), eq(target));
			then(communityNotificationService).should(never()).notifyFollow(any(), any());
		}

		@Test
		@DisplayName("비공개 계정에 이미 요청해 뒀으면 아무것도 안 하고 REQUESTED — 멱등")
		void alreadyRequested() {
			Member target = member(TARGET);
			target.changePrivacy(true);
			given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.of(target));
			given(memberBlockRepository.existsBetween(ME, TARGET)).willReturn(false);
			given(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(ME, TARGET)).willReturn(false);
			given(memberFollowRequestRepository.existsByIdRequesterIdAndIdTargetId(ME, TARGET)).willReturn(true);

			assertThat(followCommandService.follow(ME, TARGET)).isEqualTo(FollowResult.REQUESTED);

			then(memberFollowRequestRepository).should(never()).insertIfAbsent(anyLong(), anyLong(), any());
			then(memberFollowRepository).should(never()).countByIdFollowerIdAndCreatedAtGreaterThanEqual(anyLong(),
				any());
		}

		@Test
		@DisplayName("이미 팔로우 중이면 아무것도 안 하고 끝난다 — 멱등")
		void idempotent() {
			givenSocialTarget();
			given(memberBlockRepository.existsBetween(ME, TARGET)).willReturn(false);
			given(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(ME, TARGET)).willReturn(true);

			followCommandService.follow(ME, TARGET);

			then(memberFollowRepository).should(never()).insertIfAbsent(anyLong(), anyLong(), any());
			then(memberFollowRepository).should(never()).countByIdFollowerIdAndCreatedAtGreaterThanEqual(anyLong(),
				any());
		}

		@Test
		@DisplayName("나 자신은 FOLLOW400_SELF")
		void self() {
			assertThatThrownBy(() -> followCommandService.follow(ME, ME))
				.isInstanceOf(FollowException.class)
				.extracting("errorCode").isEqualTo(FollowErrorCode.FOLLOW_SELF);
			then(memberFollowRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("상대가 없거나 게스트 · 봇 · 탈퇴면 FOLLOW404_TARGET")
		void targetNotSocial() {
			given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.empty());

			assertThatThrownBy(() -> followCommandService.follow(ME, TARGET))
				.isInstanceOf(FollowException.class)
				.extracting("errorCode").isEqualTo(FollowErrorCode.FOLLOW_TARGET_NOT_FOUND);
			then(memberFollowRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("어느 쪽이든 차단했으면 FOLLOW403_BLOCKED")
		void blocked() {
			givenSocialTarget();
			given(memberBlockRepository.existsBetween(ME, TARGET)).willReturn(true);

			assertThatThrownBy(() -> followCommandService.follow(ME, TARGET))
				.isInstanceOf(FollowException.class)
				.extracting("errorCode").isEqualTo(FollowErrorCode.FOLLOW_BLOCKED);
			then(memberFollowRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("오늘 100 명을 채웠으면 FOLLOW429_DAILY_LIMIT")
		void dailyLimit() {
			givenSocialTarget();
			given(memberBlockRepository.existsBetween(ME, TARGET)).willReturn(false);
			given(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(ME, TARGET)).willReturn(false);
			given(memberFollowRepository.countByIdFollowerIdAndCreatedAtGreaterThanEqual(eq(ME), any()))
				.willReturn((long)FollowCommandService.DAILY_FOLLOW_LIMIT);

			assertThatThrownBy(() -> followCommandService.follow(ME, TARGET))
				.isInstanceOf(FollowException.class)
				.extracting("errorCode").isEqualTo(FollowErrorCode.FOLLOW_DAILY_LIMIT);
			then(memberFollowRepository).should(never()).insertIfAbsent(anyLong(), anyLong(), any());
		}

		@Test
		@DisplayName("하루 상한은 팔로우와 요청을 합쳐 센다 — 비공개 계정에 요청을 도배할 수 없다")
		void dailyLimitCountsRequests() {
			givenSocialTarget();
			given(memberBlockRepository.existsBetween(ME, TARGET)).willReturn(false);
			given(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(ME, TARGET)).willReturn(false);
			given(memberFollowRepository.countByIdFollowerIdAndCreatedAtGreaterThanEqual(eq(ME), any()))
				.willReturn(60L);
			given(memberFollowRequestRepository.countByIdRequesterIdAndCreatedAtGreaterThanEqual(eq(ME), any()))
				.willReturn(40L);

			assertThatThrownBy(() -> followCommandService.follow(ME, TARGET))
				.isInstanceOf(FollowException.class)
				.extracting("errorCode").isEqualTo(FollowErrorCode.FOLLOW_DAILY_LIMIT);
		}

		@Test
		@DisplayName("게스트는 MEMBER403_GUEST — 상대를 보기 전에 막힌다")
		void guest() {
			willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED))
				.given(memberReader).requireLoggedIn(ME);

			assertThatThrownBy(() -> followCommandService.follow(ME, TARGET))
				.isInstanceOf(MemberException.class);
			then(socialMemberReader).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("정지 · 강퇴 중이면 MEMBER403_WRITE_RESTRICTED")
		void suspended() {
			willThrow(new MemberException(MemberErrorCode.MEMBER_WRITE_RESTRICTED))
				.given(memberRestrictionReader).validateCanInteract(ME);

			assertThatThrownBy(() -> followCommandService.follow(ME, TARGET))
				.isInstanceOf(MemberException.class);
			then(memberFollowRepository).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("unfollow")
	class Unfollow {

		@Test
		@DisplayName("있든 없든 지우고 끝난다 — 제재는 보지 않는다")
		void unfollows() {
			followCommandService.unfollow(ME, TARGET);

			then(memberFollowRepository).should().deleteByPair(ME, TARGET);
			then(memberFollowRequestRepository).should().deleteByPair(ME, TARGET);
			then(memberRestrictionReader).shouldHaveNoInteractions();
			then(communityNotificationService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("보내 둔 팔로우 요청이 있었으면 요청을 지우고 상대 알림함의 요청 알림을 거둔다")
		void cancelsPendingRequest() {
			given(memberFollowRequestRepository.deleteByPair(ME, TARGET)).willReturn(1);

			followCommandService.unfollow(ME, TARGET);

			then(communityNotificationService).should().retractFollowRequestNotification(ME, TARGET);
		}

		@Test
		@DisplayName("게스트는 MEMBER403_GUEST")
		void guest() {
			willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED))
				.given(memberReader).requireLoggedIn(ME);

			assertThatThrownBy(() -> followCommandService.unfollow(ME, TARGET))
				.isInstanceOf(MemberException.class);
			then(memberFollowRepository).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("followEachOther")
	class FollowEachOther {

		@Test
		@DisplayName("둘 다 상대가 될 수 있고 차단 관계가 아니면 양방향으로 넣는다 — 상한은 세지 않는다")
		void both() {
			given(socialMemberReader.findSocial(ME)).willReturn(Optional.of(member(ME)));
			givenSocialTarget();
			given(memberBlockRepository.existsBetween(ME, TARGET)).willReturn(false);

			followCommandService.followEachOther(ME, TARGET);

			then(memberFollowRepository).should().insertIfAbsent(eq(ME), eq(TARGET), any());
			then(memberFollowRepository).should().insertIfAbsent(eq(TARGET), eq(ME), any());
			then(memberFollowRepository).should(never()).countByIdFollowerIdAndCreatedAtGreaterThanEqual(anyLong(),
				any());
		}

		@Test
		@DisplayName("한쪽이 게스트 · 봇 · 탈퇴면 조용히 건너뛴다")
		void oneNotSocial() {
			given(socialMemberReader.findSocial(ME)).willReturn(Optional.of(member(ME)));
			given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.empty());

			followCommandService.followEachOther(ME, TARGET);

			then(memberFollowRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("차단 관계면 조용히 건너뛴다")
		void blocked() {
			given(socialMemberReader.findSocial(ME)).willReturn(Optional.of(member(ME)));
			givenSocialTarget();
			given(memberBlockRepository.existsBetween(ME, TARGET)).willReturn(true);

			followCommandService.followEachOther(ME, TARGET);

			then(memberFollowRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("같은 사람이거나 id 가 비면 아무것도 안 한다")
		void sameOrNull() {
			followCommandService.followEachOther(ME, ME);
			followCommandService.followEachOther(null, TARGET);

			then(socialMemberReader).shouldHaveNoInteractions();
			then(memberFollowRepository).shouldHaveNoInteractions();
		}
	}
}
