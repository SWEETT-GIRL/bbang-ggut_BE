package sweetgirl.croissant.reward.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 보상 수치 한 칸. 키의 목록은 {@code RewardSettingKey} 가 정본이다. */
@Entity
@Table(name = "reward_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RewardSetting {

	@Id
	@Column(name = "setting_key", length = 50)
	private String settingKey;

	@Column(nullable = false)
	private int intValue;

	private LocalDateTime updatedAt;

	private RewardSetting(String settingKey, int intValue, LocalDateTime updatedAt) {
		this.settingKey = settingKey;
		this.intValue = intValue;
		this.updatedAt = updatedAt;
	}

	public static RewardSetting of(String settingKey, int intValue, LocalDateTime updatedAt) {
		return new RewardSetting(settingKey, intValue, updatedAt);
	}

	public void changeValue(int intValue, LocalDateTime updatedAt) {
		this.intValue = intValue;
		this.updatedAt = updatedAt;
	}
}
