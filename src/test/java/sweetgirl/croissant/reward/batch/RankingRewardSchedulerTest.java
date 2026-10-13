package sweetgirl.croissant.reward.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.service.RankingRewardService;
import sweetgirl.croissant.reward.service.RewardSettings;

@ExtendWith(MockitoExtension.class)
class RankingRewardSchedulerTest {

	@Mock
	private RankingRewardService rankingRewardService;

	@Mock
	private RewardSettings rewardSettings;

	@InjectMocks
	private RankingRewardScheduler rankingRewardScheduler;

	@Test
	@DisplayName("스위치가 꺼져 있으면 보상 줄을 만들지 않는다")
	void skipsWhenOff() {
		given(rewardSettings.isOn(RewardSettingKey.RANKING_ISSUE)).willReturn(false);

		rankingRewardScheduler.issueLastWeek();

		then(rankingRewardService).should(never()).issueFor(any(LocalDate.class));
	}

	@Test
	@DisplayName("스위치가 켜져 있으면 지난주 보상 줄을 만든다")
	void issuesWhenOn() {
		given(rewardSettings.isOn(RewardSettingKey.RANKING_ISSUE)).willReturn(true);

		rankingRewardScheduler.issueLastWeek();

		then(rankingRewardService).should().issueFor(any(LocalDate.class));
	}

	@Test
	@DisplayName("만들다 실패해도 예외를 밖으로 내지 않는다 — 다음 실행에서 다시 한다")
	void swallowsFailure() {
		given(rewardSettings.isOn(RewardSettingKey.RANKING_ISSUE)).willReturn(true);
		willThrow(new IllegalStateException("스냅샷 없음")).given(rankingRewardService).issueFor(any(LocalDate.class));

		rankingRewardScheduler.issueLastWeek();

		then(rankingRewardService).should().issueFor(any(LocalDate.class));
	}
}
