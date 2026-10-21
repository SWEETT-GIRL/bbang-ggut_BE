package sweetgirl.croissant.reward.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.reward.entity.RewardSetting;

public interface RewardSettingRepository extends JpaRepository<RewardSetting, String> {
	// 키 하나를 읽거나 표를 통째로 읽는 것뿐이라 따로 둔 쿼리가 없다.
}
