package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.service.GrantResult.Outcome;

@ExtendWith(MockitoExtension.class)
class EarnRewardServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private CrumbGrantService crumbGrantService;
	@Mock
	private RewardSettings rewardSettings;
	@Mock
	private MemberRepository memberRepository;

	@InjectMocks
	private EarnRewardService earnRewardService;

	@BeforeEach
	void rules() {
		lenient().when(rewardSettings.get(RewardSettingKey.SAVE_STORE)).thenReturn(2);
		lenient().when(rewardSettings.get(RewardSettingKey.SAVE_STORE_DAILY_CAP)).thenReturn(3);
		lenient().when(rewardSettings.get(RewardSettingKey.AD)).thenReturn(20);
		lenient().when(rewardSettings.get(RewardSettingKey.AD_DAILY_CAP)).thenReturn(2);
	}

	private void givenSaveGrant(GrantResult result) {
		given(crumbGrantService.grantWithDailyCap(
			eq(7L), eq(CrumbReason.SAVE_STORE), eq(2), eq("save:10"), eq(RewardRefType.STORE), eq(10L), eq(3), any()))
			.willReturn(result);
	}

	@Test
	@DisplayName("빵집을 새로 담으면 +2")
	void saveStore() {
		givenSaveGrant(new GrantResult(Outcome.GRANTED, 2, 10, 12));

		List<RewardItemResponse> rewards = earnRewardService.rewardStoreSaved(MEMBER_ID, 10L);

		assertThat(rewards).singleElement().satisfies(reward -> {
			assertThat(reward.reason()).isEqualTo(CrumbReason.SAVE_STORE);
			assertThat(reward.amount()).isEqualTo(2);
			assertThat(reward.capped()).isFalse();
		});
	}

	@Test
	@DisplayName("하루 4 번째 담기는 capped 로 싣는다")
	void saveStoreCapped() {
		givenSaveGrant(new GrantResult(Outcome.CAPPED, 0, 16, 16));

		assertThat(earnRewardService.rewardStoreSaved(MEMBER_ID, 10L)).singleElement()
			.satisfies(reward -> assertThat(reward.capped()).isTrue());
	}

	@Test
	@DisplayName("담았다 풀고 다시 담은 가게는 보상이 없다 — 같은 가게는 평생 한 번")
	void saveStoreAgain() {
		givenSaveGrant(new GrantResult(Outcome.ALREADY_GRANTED, 0, 12, 12));

		assertThat(earnRewardService.rewardStoreSaved(MEMBER_ID, 10L)).isEmpty();
	}

	@Test
	@DisplayName("광고를 끝까지 보면 +20 — 멱등 키는 구글의 transaction id 다")
	void adWatched() {
		given(crumbGrantService.grantWithDailyCap(
			eq(7L), eq(CrumbReason.AD), eq(20), eq("ad:TX9"), isNull(), isNull(), eq(2), any()))
			.willReturn(new GrantResult(Outcome.GRANTED, 20, 12, 32));

		assertThat(earnRewardService.rewardAdWatched(MEMBER_ID, "TX9")).isTrue();
	}

	@Test
	@DisplayName("하루 3 번째 광고는 주지 않는다")
	void adOverCap() {
		given(crumbGrantService.grantWithDailyCap(
			eq(7L), eq(CrumbReason.AD), eq(20), eq("ad:TX10"), isNull(), isNull(), eq(2), any()))
			.willReturn(new GrantResult(Outcome.CAPPED, 0, 52, 52));

		assertThat(earnRewardService.rewardAdWatched(MEMBER_ID, "TX10")).isFalse();
	}

	@ParameterizedTest(name = "오늘 {0}번 봤으면 {1}번 남는다")
	@CsvSource({"0, 2", "1, 1", "2, 0", "5, 0"})
	@DisplayName("오늘 남은 광고 횟수")
	void adsLeftToday(long watched, int expectedLeft) {
		given(crumbGrantService.countToday(MEMBER_ID, CrumbReason.AD)).willReturn(watched);

		assertThat(earnRewardService.adsLeftToday(MEMBER_ID)).isEqualTo(expectedLeft);
	}

	@Test
	@DisplayName("게스트와 없는 회원은 부스러기를 받을 수 없다")
	void canEarn() {
		Member guest = mock(Member.class);
		given(guest.isGuest()).willReturn(true);
		given(memberRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(guest));
		given(memberRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.empty());
		given(memberRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(mock(Member.class)));

		assertThat(earnRewardService.canEarn(1L)).isFalse();
		assertThat(earnRewardService.canEarn(2L)).isFalse();
		assertThat(earnRewardService.canEarn(3L)).isTrue();
	}
}
