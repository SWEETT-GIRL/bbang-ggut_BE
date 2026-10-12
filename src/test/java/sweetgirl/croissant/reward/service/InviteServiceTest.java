package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.enums.RecordType;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.response.InviteResponse;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.entity.MemberInvite;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.repository.MemberInviteRepository;
import sweetgirl.croissant.reward.service.GrantResult.Outcome;

@ExtendWith(MockitoExtension.class)
class InviteServiceTest {

	private static final Long INVITER_ID = 1L;
	private static final Long INVITEE_ID = 2L;
	private static final Long INVITE_ID = 9L;

	@Mock
	private MemberInviteRepository memberInviteRepository;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private InviteCodeGenerator inviteCodeGenerator;
	@Mock
	private CrumbGrantService crumbGrantService;
	@Mock
	private RewardPendingService rewardPendingService;
	@Mock
	private RewardSettings rewardSettings;
	@Mock
	private MemberReader memberReader;

	@InjectMocks
	private InviteService inviteService;

	@BeforeEach
	void rules() {
		lenient().when(rewardSettings.get(RewardSettingKey.INVITE)).thenReturn(100);
		lenient().when(rewardSettings.get(RewardSettingKey.INVITE_INVITER_CAP)).thenReturn(10);
		lenient().when(rewardSettings.get(RewardSettingKey.INVITE_EXPIRE_DAYS)).thenReturn(30);
	}

	private Member member(Long id, LocalDateTime registeredAt) {
		Member member = Member.registering("회원" + id, registeredAt);
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private Member newInvitee() {
		Member invitee = member(INVITEE_ID, LocalDateTime.now().minusDays(1));
		lenient().when(memberReader.getMember(INVITEE_ID)).thenReturn(invitee);
		return invitee;
	}

	private MemberInvite invite(LocalDateTime createdAt, LocalDateTime rewardedAt) {
		MemberInvite invite = mock(MemberInvite.class, org.mockito.Mockito.withSettings()
			.defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
		ReflectionTestUtils.setField(invite, "id", INVITE_ID);
		ReflectionTestUtils.setField(invite, "inviterId", INVITER_ID);
		ReflectionTestUtils.setField(invite, "inviteeId", INVITEE_ID);
		ReflectionTestUtils.setField(invite, "createdAt", createdAt);
		ReflectionTestUtils.setField(invite, "rewardedAt", rewardedAt);
		return invite;
	}

	@Nested
	@DisplayName("코드 넣기")
	class Accept {

		private void givenInviterWithCode(String code) {
			given(memberRepository.findByInviteCodeAndDeletedAtIsNull(code))
				.willReturn(Optional.of(member(INVITER_ID, LocalDateTime.now().minusMonths(3))));
		}

		@Test
		@DisplayName("가입 7 일 안, 기록 없는 회원이 남의 코드를 넣으면 초대가 남는다 — 소문자로 넣어도 된다")
		void accepts() {
			newInvitee();
			givenInviterWithCode("ABCD2345");
			given(memberInviteRepository.insertIfAbsent(eq(INVITER_ID), eq(INVITEE_ID), any())).willReturn(1);

			assertThat(inviteService.accept(INVITEE_ID, " abcd2345 ").inviterNickname()).isEqualTo("회원1");
		}

		@Test
		@DisplayName("자기 코드는 REWARD400_7")
		void ownCode() {
			newInvitee();
			given(memberRepository.findByInviteCodeAndDeletedAtIsNull("MYCODE22"))
				.willReturn(Optional.of(member(INVITEE_ID, LocalDateTime.now())));

			assertThatThrownBy(() -> inviteService.accept(INVITEE_ID, "MYCODE22"))
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_INVITE_OWN_CODE);
		}

		@Test
		@DisplayName("이미 코드를 넣은 회원은 REWARD409_4")
		void alreadyAccepted() {
			newInvitee();
			given(memberInviteRepository.existsByInviteeId(INVITEE_ID)).willReturn(true);

			assertThatThrownBy(() -> inviteService.accept(INVITEE_ID, "ABCD2345"))
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_INVITE_ALREADY_ACCEPTED);
		}

		@Test
		@DisplayName("가입한 지 7 일이 지났으면 REWARD400_6")
		void tooLate() {
			given(memberReader.getMember(INVITEE_ID)).willReturn(member(INVITEE_ID, LocalDateTime.now().minusDays(8)));

			assertThatThrownBy(() -> inviteService.accept(INVITEE_ID, "ABCD2345"))
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_INVITE_TOO_LATE);
		}

		@Test
		@DisplayName("이미 빵을 기록한 회원은 REWARD400_6")
		void hasRecord() {
			newInvitee();
			given(breadRecordRepository.countByMemberIdAndRecordTypeAndDeletedAtIsNull(INVITEE_ID, RecordType.NORMAL))
				.willReturn(1L);

			assertThatThrownBy(() -> inviteService.accept(INVITEE_ID, "ABCD2345"))
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_INVITE_TOO_LATE);
		}

		@Test
		@DisplayName("없는 코드는 REWARD404_4")
		void unknownCode() {
			newInvitee();
			given(memberRepository.findByInviteCodeAndDeletedAtIsNull("NOPE2222")).willReturn(Optional.empty());

			assertThatThrownBy(() -> inviteService.accept(INVITEE_ID, "NOPE2222"))
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_INVITE_CODE_NOT_FOUND);
		}

		@Test
		@DisplayName("다시 가입한 회원은 REWARD403_2")
		void rejoined() {
			Member rejoined = Member.rejoining("회원2", 77L, LocalDateTime.now());
			ReflectionTestUtils.setField(rejoined, "id", INVITEE_ID);
			given(memberReader.getMember(INVITEE_ID)).willReturn(rejoined);

			assertThatThrownBy(() -> inviteService.accept(INVITEE_ID, "ABCD2345"))
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_INVITE_REJOINED);
		}

		@Test
		@DisplayName("내가 초대한 친구의 코드는 넣을 수 없다 — REWARD400_8")
		void mutual() {
			newInvitee();
			givenInviterWithCode("ABCD2345");
			given(memberInviteRepository.existsByInviterIdAndInviteeId(INVITEE_ID, INVITER_ID)).willReturn(true);

			assertThatThrownBy(() -> inviteService.accept(INVITEE_ID, "ABCD2345"))
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_INVITE_MUTUAL);
			then(memberInviteRepository).should(never()).insertIfAbsent(anyLong(), anyLong(), any());
		}
	}

	@Nested
	@DisplayName("첫 빵으로 성사")
	class RewardFirstRecord {

		private void givenInviterActive() {
			given(memberRepository.findByIdAndDeletedAtIsNull(INVITER_ID))
				.willReturn(Optional.of(member(INVITER_ID, LocalDateTime.now().minusMonths(3))));
		}

		private void givenGrants() {
			lenient().when(crumbGrantService.grant(INVITEE_ID, CrumbReason.INVITE_INVITEE, 100, "invite:9:invitee",
				RewardRefType.INVITE, INVITE_ID)).thenReturn(new GrantResult(Outcome.GRANTED, 100, 10, 110));
			lenient().when(crumbGrantService.grant(INVITER_ID, CrumbReason.INVITE_INVITER, 100, "invite:9:inviter",
				RewardRefType.INVITE, INVITE_ID)).thenReturn(new GrantResult(Outcome.GRANTED, 100, 0, 100));
		}

		@Test
		@DisplayName("코드 입력 30 일 안의 첫 빵이면 둘 다 +100 — 초대한 사람 몫은 연출 대기로 남긴다")
		void bothRewarded() {
			MemberInvite invite = invite(LocalDateTime.now().minusDays(29), null);
			given(memberInviteRepository.findByInviteeId(INVITEE_ID)).willReturn(Optional.of(invite));
			given(memberInviteRepository.markRewarded(eq(INVITE_ID), any())).willReturn(1);
			givenInviterActive();
			given(memberInviteRepository.countByInviterIdAndRewardedAtIsNotNull(INVITER_ID)).willReturn(1L);
			givenGrants();

			List<RewardItemResponse> rewards = inviteService.rewardFirstRecord(newInvitee());

			assertThat(rewards).singleElement().satisfies(reward -> {
				assertThat(reward.reason()).isEqualTo(CrumbReason.INVITE_INVITEE);
				assertThat(reward.amount()).isEqualTo(100);
			});
			then(rewardPendingService).should().add(eq(INVITER_ID), eq("invite:9:inviter"), any());
		}

		@Test
		@DisplayName("초대한 사람의 11 번째 성사 — 친구만 받는다")
		void inviterOverCap() {
			MemberInvite invite = invite(LocalDateTime.now().minusDays(1), null);
			given(memberInviteRepository.findByInviteeId(INVITEE_ID)).willReturn(Optional.of(invite));
			given(memberInviteRepository.markRewarded(eq(INVITE_ID), any())).willReturn(1);
			givenInviterActive();
			given(memberInviteRepository.countByInviterIdAndRewardedAtIsNotNull(INVITER_ID)).willReturn(11L);
			givenGrants();

			assertThat(inviteService.rewardFirstRecord(newInvitee())).hasSize(1);
			then(crumbGrantService).should(never()).grant(eq(INVITER_ID), any(), anyInt(), anyString(), any(), any());
			// 성사 수를 세기 전에 초대한 사람의 지갑을 잠근다
			then(crumbGrantService).should().serialize(INVITER_ID);
		}

		@Test
		@DisplayName("코드 입력 31 일째의 첫 빵은 만료 — 둘 다 받지 못한다")
		void expired() {
			MemberInvite invite = invite(LocalDateTime.now().minusDays(31), null);
			given(memberInviteRepository.findByInviteeId(INVITEE_ID)).willReturn(Optional.of(invite));

			assertThat(inviteService.rewardFirstRecord(newInvitee())).isEmpty();
			then(memberInviteRepository).should(never()).markRewarded(anyLong(), any());
			then(crumbGrantService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("초대한 사람이 탈퇴했으면 친구만 받는다")
		void inviterWithdrew() {
			MemberInvite invite = invite(LocalDateTime.now().minusDays(1), null);
			given(memberInviteRepository.findByInviteeId(INVITEE_ID)).willReturn(Optional.of(invite));
			given(memberInviteRepository.markRewarded(eq(INVITE_ID), any())).willReturn(1);
			given(memberRepository.findByIdAndDeletedAtIsNull(INVITER_ID)).willReturn(Optional.empty());
			givenGrants();

			assertThat(inviteService.rewardFirstRecord(newInvitee())).hasSize(1);
			then(crumbGrantService).should(never()).serialize(INVITER_ID);
			then(rewardPendingService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("두 번째 기록에는 없다 — 이미 성사된 초대다")
		void secondRecord() {
			MemberInvite invite = invite(LocalDateTime.now().minusDays(5), LocalDateTime.now().minusDays(4));
			given(memberInviteRepository.findByInviteeId(INVITEE_ID)).willReturn(Optional.of(invite));

			assertThat(inviteService.rewardFirstRecord(newInvitee())).isEmpty();
			then(crumbGrantService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("첫 기록이 나란히 들어와 다른 쪽이 먼저 성사시켰으면 이쪽은 주지 않는다")
		void lostTheRace() {
			MemberInvite invite = invite(LocalDateTime.now().minusDays(1), null);
			given(memberInviteRepository.findByInviteeId(INVITEE_ID)).willReturn(Optional.of(invite));
			given(memberInviteRepository.markRewarded(eq(INVITE_ID), any())).willReturn(0);

			assertThat(inviteService.rewardFirstRecord(newInvitee())).isEmpty();
			then(crumbGrantService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("초대받지 않은 회원은 아무 일도 없다")
		void notInvited() {
			given(memberInviteRepository.findByInviteeId(INVITEE_ID)).willReturn(Optional.empty());

			assertThat(inviteService.rewardFirstRecord(newInvitee())).isEmpty();
		}
	}

	@Nested
	@DisplayName("내 초대 현황")
	class GetMyInvite {

		@Test
		@DisplayName("코드가 없으면 만들어 붙이고, 기다리는 친구와 성사 수를 센다 — 기한이 지난 초대는 기다리는 목록에서 빠진다")
		void summary() {
			Member me = member(INVITER_ID, LocalDateTime.now().minusMonths(3));
			given(memberReader.getMember(INVITER_ID)).willReturn(me);
			given(inviteCodeGenerator.generate()).willReturn("ABCD2345");
			given(memberRepository.existsByInviteCode("ABCD2345")).willReturn(false);
			given(memberRepository.findInviteCode(INVITER_ID)).willReturn(Optional.of("ABCD2345"));
			MemberInvite waiting = invite(LocalDateTime.now().minusDays(3), null);
			MemberInvite expired = invite(LocalDateTime.now().minusDays(40), null);
			ReflectionTestUtils.setField(expired, "inviteeId", 3L);
			MemberInvite rewarded = invite(LocalDateTime.now().minusDays(10), LocalDateTime.now().minusDays(9));
			ReflectionTestUtils.setField(rewarded, "inviteeId", 4L);
			given(memberInviteRepository.findAllByInviterIdOrderByIdAsc(INVITER_ID))
				.willReturn(List.of(waiting, expired, rewarded));
			given(memberRepository.findAllById(List.of(INVITEE_ID)))
				.willReturn(List.of(member(INVITEE_ID, LocalDateTime.now().minusDays(3))));
			given(memberInviteRepository.findByInviteeId(INVITER_ID)).willReturn(Optional.empty());

			InviteResponse response = inviteService.getMyInvite(INVITER_ID);

			assertThat(response.code()).isEqualTo("ABCD2345");
			assertThat(response.joinedCount()).isEqualTo(3);
			assertThat(response.rewardedCount()).isEqualTo(1);
			assertThat(response.waiting()).singleElement()
				.satisfies(friend -> assertThat(friend.nickname()).isEqualTo("회원2"));
			assertThat(response.cap()).isEqualTo(10);
			assertThat(response.reward()).isEqualTo(100);
			assertThat(response.invitedBy()).isNull();
			// 가입한 지 석 달이라 이제 코드를 넣을 수 없다
			assertThat(response.canEnterCode()).isFalse();
			then(memberRepository).should().assignInviteCodeIfAbsent(INVITER_ID, "ABCD2345");
		}
	}
}
