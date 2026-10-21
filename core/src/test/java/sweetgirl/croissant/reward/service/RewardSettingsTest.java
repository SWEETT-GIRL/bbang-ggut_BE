package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.reward.entity.RewardSetting;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.repository.RewardSettingRepository;

@ExtendWith(MockitoExtension.class)
class RewardSettingsTest {

	@Mock
	private RewardSettingRepository rewardSettingRepository;

	@InjectMocks
	private RewardSettings rewardSettings;

	@Test
	@DisplayName("표에 있는 값이 기본값을 덮는다")
	void databaseValueWins() {
		given(rewardSettingRepository.findAll())
			.willReturn(List.of(RewardSetting.of("record", 12, LocalDateTime.now())));

		assertThat(rewardSettings.get(RewardSettingKey.RECORD)).isEqualTo(12);
	}

	@Test
	@DisplayName("표에 행이 없으면 기본값으로 답한다 — 시드가 빠져도 보상이 0 으로 나가지 않는다")
	void fallsBackToDefault() {
		given(rewardSettingRepository.findAll()).willReturn(List.of());

		assertThat(rewardSettings.get(RewardSettingKey.WAKE_FRIEND)).isEqualTo(600);
		assertThat(rewardSettings.attendance()).containsExactly(5, 5, 10, 5, 10, 10, 30);
	}

	@Test
	@DisplayName("모르는 키가 표에 있어도 무시한다")
	void ignoresUnknownKey() {
		given(rewardSettingRepository.findAll())
			.willReturn(List.of(RewardSetting.of("no-such-key", 1, LocalDateTime.now())));

		assertThat(rewardSettings.all()).hasSize(RewardSettingKey.values().length);
	}

	@Test
	@DisplayName("잠깐은 들고 있다가, 비우면 다시 읽는다")
	void cachesUntilEvicted() {
		given(rewardSettingRepository.findAll()).willReturn(List.of());

		rewardSettings.get(RewardSettingKey.RECORD);
		rewardSettings.get(RewardSettingKey.LETTER);
		then(rewardSettingRepository).should(times(1)).findAll();

		rewardSettings.evict();
		rewardSettings.get(RewardSettingKey.RECORD);
		then(rewardSettingRepository).should(times(2)).findAll();
	}
}
