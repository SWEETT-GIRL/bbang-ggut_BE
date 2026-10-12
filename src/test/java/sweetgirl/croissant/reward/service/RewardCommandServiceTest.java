package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.enums.RecordType;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.dto.response.RewardClaimResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.CarryOver;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardItemType;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.repository.MemberCrumbWalletRepository;
import sweetgirl.croissant.reward.service.GrantResult.Outcome;

@ExtendWith(MockitoExtension.class)
class RewardCommandServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private CrumbGrantService crumbGrantService;
	@Mock
	private RewardPendingService rewardPendingService;
	@Mock
	private RewardSettings rewardSettings;
	@Mock
	private RewardMemberPolicy rewardMemberPolicy;
	@Mock
	private MemberCrumbWalletRepository walletRepository;
	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private MemberReader memberReader;

	@InjectMocks
	private RewardCommandService rewardCommandService;

	private Member givenMember() {
		Member member = mock(Member.class);
		given(memberReader.getMember(MEMBER_ID)).willReturn(member);
		return member;
	}

	@Nested
	@DisplayName("1.x 사용자 이관")
	class CarryOverIfNeeded {

		private void givenCarryOverRules() {
			given(rewardSettings.get(RewardSettingKey.CARRY_OVER_BASE)).willReturn(50);
			given(rewardSettings.get(RewardSettingKey.CARRY_OVER_PER_RECORD)).willReturn(5);
			given(rewardSettings.get(RewardSettingKey.CARRY_OVER_MAX)).willReturn(300);
		}

		@ParameterizedTest(name = "기록 {0}개 → {1}")
		@CsvSource({"0, 50", "12, 110", "50, 300", "60, 300"})
		@DisplayName("50 + 기록 1개당 5, 합쳐서 300 까지 준다")
		void grantsByRecordCount(long recordCount, int expected) {
			Member member = givenMember();
			given(rewardMemberPolicy.isCarryOverTarget(member)).willReturn(true);
			given(walletRepository.findCarriedOverAt(MEMBER_ID)).willReturn(Optional.empty());
			given(breadRecordRepository.countByMemberIdAndRecordTypeAndDeletedAtIsNull(MEMBER_ID, RecordType.NORMAL))
				.willReturn(recordCount);
			givenCarryOverRules();
			given(crumbGrantService.grant(7L, CrumbReason.CARRY_OVER, expected, "carry_over", null, null))
				.willReturn(new GrantResult(Outcome.GRANTED, expected, 0, expected));

			Optional<CarryOver> carryOver = rewardCommandService.carryOverIfNeeded(MEMBER_ID);

			assertThat(carryOver).contains(new CarryOver(true, expected, (int)recordCount));
			then(walletRepository).should().markCarriedOver(eq(MEMBER_ID), any(LocalDateTime.class));
		}

		@Test
		@DisplayName("2.0 에서 새로 온 회원은 받지 않는다")
		void newMember() {
			Member member = givenMember();
			given(rewardMemberPolicy.isCarryOverTarget(member)).willReturn(false);

			assertThat(rewardCommandService.carryOverIfNeeded(MEMBER_ID)).isEmpty();
			then(crumbGrantService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("이미 받았으면 다시 계산하지도 않는다")
		void alreadyCarriedOver() {
			Member member = givenMember();
			given(rewardMemberPolicy.isCarryOverTarget(member)).willReturn(true);
			given(walletRepository.findCarriedOverAt(MEMBER_ID)).willReturn(Optional.of(LocalDateTime.now()));

			assertThat(rewardCommandService.carryOverIfNeeded(MEMBER_ID)).isEmpty();
			then(breadRecordRepository).shouldHaveNoInteractions();
			then(crumbGrantService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("동시에 들어온 다른 요청이 먼저 줬으면 이쪽은 안내를 띄우지 않는다")
		void lostTheRace() {
			Member member = givenMember();
			given(rewardMemberPolicy.isCarryOverTarget(member)).willReturn(true);
			given(walletRepository.findCarriedOverAt(MEMBER_ID)).willReturn(Optional.empty());
			givenCarryOverRules();
			given(crumbGrantService.grant(7L, CrumbReason.CARRY_OVER, 50, "carry_over", null, null))
				.willReturn(new GrantResult(Outcome.ALREADY_GRANTED, 0, 50, 50));

			assertThat(rewardCommandService.carryOverIfNeeded(MEMBER_ID)).isEmpty();
		}

		@Test
		@DisplayName("제재 중이라 못 줬으면 준 것으로 남기지 않는다 — 제재가 풀린 뒤에 받는다")
		void restricted() {
			Member member = givenMember();
			given(rewardMemberPolicy.isCarryOverTarget(member)).willReturn(true);
			given(walletRepository.findCarriedOverAt(MEMBER_ID)).willReturn(Optional.empty());
			givenCarryOverRules();
			given(crumbGrantService.grant(7L, CrumbReason.CARRY_OVER, 50, "carry_over", null, null))
				.willReturn(new GrantResult(Outcome.RESTRICTED, 0, 0, 0));

			assertThat(rewardCommandService.carryOverIfNeeded(MEMBER_ID)).isEmpty();
			then(walletRepository).should(never()).markCarriedOver(anyLong(), any());
		}
	}

	@Nested
	@DisplayName("환영 선물")
	class ClaimWelcome {

		@Test
		@DisplayName("새 회원은 한 번 받는다")
		void newMember() {
			Member member = givenMember();
			given(rewardMemberPolicy.isWelcomeTarget(member)).willReturn(true);
			given(rewardSettings.get(RewardSettingKey.WELCOME)).willReturn(25);
			given(crumbGrantService.grant(7L, CrumbReason.WELCOME, 25, "welcome", null, null))
				.willReturn(new GrantResult(Outcome.GRANTED, 25, 0, 25));

			RewardClaimResponse response = rewardCommandService.claimWelcome(MEMBER_ID);

			assertThat(response.alreadyDone()).isFalse();
			assertThat(response.rewards()).singleElement().satisfies(reward -> {
				assertThat(reward.type()).isEqualTo(RewardItemType.CRUMBS);
				assertThat(reward.amount()).isEqualTo(25);
				assertThat(reward.to()).isEqualTo(25);
				assertThat(reward.reason()).isEqualTo(CrumbReason.WELCOME);
				assertThat(reward.capped()).isFalse();
			});
		}

		@Test
		@DisplayName("두 번째 호출은 alreadyDone 이고 보상이 없다")
		void secondCall() {
			Member member = givenMember();
			given(rewardMemberPolicy.isWelcomeTarget(member)).willReturn(true);
			given(rewardSettings.get(RewardSettingKey.WELCOME)).willReturn(25);
			given(crumbGrantService.grant(7L, CrumbReason.WELCOME, 25, "welcome", null, null))
				.willReturn(new GrantResult(Outcome.ALREADY_GRANTED, 0, 25, 25));

			RewardClaimResponse response = rewardCommandService.claimWelcome(MEMBER_ID);

			assertThat(response).isEqualTo(new RewardClaimResponse(true, List.of()));
		}

		@Test
		@DisplayName("이관 대상이거나 재가입한 회원은 받지 않는다")
		void notTarget() {
			Member member = givenMember();
			given(rewardMemberPolicy.isWelcomeTarget(member)).willReturn(false);

			RewardClaimResponse response = rewardCommandService.claimWelcome(MEMBER_ID);

			assertThat(response).isEqualTo(RewardClaimResponse.done());
			then(crumbGrantService).should(never()).grant(anyLong(), any(), anyInt(), any(), any(), any());
		}

		@Test
		@DisplayName("게스트는 403 이다")
		void guest() {
			willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED))
				.given(memberReader).requireLoggedIn(MEMBER_ID);

			assertThatThrownBy(() -> rewardCommandService.claimWelcome(MEMBER_ID))
				.isInstanceOf(MemberException.class)
				.extracting("errorCode").isEqualTo(MemberErrorCode.GUEST_LOGIN_REQUIRED);
			then(crumbGrantService).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("연출한 보상 확인")
	class AcknowledgePending {

		@Test
		@DisplayName("내 것만 지우도록 회원 id 와 함께 넘긴다")
		void acknowledges() {
			rewardCommandService.acknowledgePending(MEMBER_ID, List.of(3L, 4L));

			then(rewardPendingService).should().acknowledge(MEMBER_ID, List.of(3L, 4L));
		}

		@Test
		@DisplayName("게스트는 403 이다")
		void guest() {
			willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED))
				.given(memberReader).requireLoggedIn(MEMBER_ID);

			assertThatThrownBy(() -> rewardCommandService.acknowledgePending(MEMBER_ID, List.of(3L)))
				.isInstanceOf(MemberException.class);
			then(rewardPendingService).shouldHaveNoInteractions();
		}
	}
}
