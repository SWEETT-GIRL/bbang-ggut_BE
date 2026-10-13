package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.reward.entity.RewardSetting;
import sweetgirl.croissant.reward.repository.RewardSettingRepository;

@ExtendWith(MockitoExtension.class)
class RewardLaunchServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 14, 7, 42);

	@Mock
	private RewardSettingRepository rewardSettingRepository;

	@Mock
	private RewardSettings rewardSettings;

	@InjectMocks
	private RewardLaunchService rewardLaunchService;

	@Test
	@DisplayName("아직 안 열었으면 지금을 출시 시각으로 적고 랭킹 주간 보상을 켠다")
	void opens() {
		given(rewardSettingRepository.findById(any(String.class))).willReturn(Optional.empty());

		boolean opened = rewardLaunchService.openIfNotYet(NOW);

		assertThat(opened).isTrue();
		ArgumentCaptor<RewardSetting> saved = ArgumentCaptor.forClass(RewardSetting.class);
		then(rewardSettingRepository).should(times(3)).save(saved.capture());
		List<RewardSetting> settings = saved.getAllValues();
		assertThat(settings).extracting(RewardSetting::getSettingKey)
			.containsExactly("carry-over.before-date", "carry-over.before-time", "ranking.issue");
		assertThat(settings).extracting(RewardSetting::getIntValue).containsExactly(20261103, 1407, 1);
		then(rewardSettings).should().evict();
	}

	@Test
	@DisplayName("행은 있는데 값이 0 이면 아직 안 연 것이다 — 그 행을 고쳐 쓴다")
	void opensWhenRowIsZero() {
		RewardSetting date = RewardSetting.of("carry-over.before-date", 0, NOW.minusDays(1));
		given(rewardSettingRepository.findById(any(String.class))).willReturn(Optional.empty());
		given(rewardSettingRepository.findById("carry-over.before-date")).willReturn(Optional.of(date));

		assertThat(rewardLaunchService.openIfNotYet(NOW)).isTrue();
		assertThat(date.getIntValue()).isEqualTo(20261103);
	}

	@Test
	@DisplayName("출시 시각이 이미 있으면 아무것도 바꾸지 않는다 — 선을 뒤로 밀지 않는다")
	void keepsExistingCutoff() {
		given(rewardSettingRepository.findById("carry-over.before-date"))
			.willReturn(Optional.of(RewardSetting.of("carry-over.before-date", 20261101, NOW.minusDays(2))));

		assertThat(rewardLaunchService.openIfNotYet(NOW)).isFalse();

		then(rewardSettingRepository).should(never()).save(any(RewardSetting.class));
		then(rewardSettings).should(never()).evict();
	}
}
