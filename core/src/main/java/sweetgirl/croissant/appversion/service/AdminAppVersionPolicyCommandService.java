package sweetgirl.croissant.appversion.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.appversion.code.AppVersionErrorCode;
import sweetgirl.croissant.appversion.dto.request.AppVersionPolicyUpdateRequest;
import sweetgirl.croissant.appversion.dto.response.AppVersionPolicyResponse;
import sweetgirl.croissant.appversion.entity.AppVersionPolicy;
import sweetgirl.croissant.appversion.enums.AppPlatform;
import sweetgirl.croissant.appversion.exception.AppVersionException;
import sweetgirl.croissant.appversion.repository.AppVersionPolicyRepository;
import sweetgirl.croissant.reward.service.CrumbGrantService;
import sweetgirl.croissant.reward.service.RewardLaunchService;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminAppVersionPolicyCommandService {

	/** 이 버전부터 리워드가 있는 앱이다. 강제 업데이트가 여기에 닿으면 옛 앱이 사라진다 */
	static final String REWARD_LAUNCH_VERSION = "2.0.0";

	private final AppVersionPolicyRepository appVersionPolicyRepository;
	private final RewardLaunchService rewardLaunchService;

	/** 플랫폼당 한 행 — 없으면 만들고 있으면 통째로 바꾼다. */
	public AppVersionPolicyResponse upsert(AppPlatform platform, AppVersionPolicyUpdateRequest request) {
		String min = blankToNull(request.minSupportedVersion());
		String latest = blankToNull(request.latestVersion());
		validate(min, latest);

		AppVersionPolicy policy = appVersionPolicyRepository.findByPlatform(platform)
			.orElseGet(() -> appVersionPolicyRepository.save(
				AppVersionPolicy.builder().platform(platform).build()));
		policy.update(min, latest, blankToNull(request.storeUrl()), blankToNull(request.message()));
		openRewardsIfLaunched(min);
		return AppVersionPolicyResponse.from(policy);
	}

	/**
	 * 강제 업데이트를 2.0 이상으로 올리는 순간이 리워드의 출시 시각이다 — 출시 날 사람이 따로 누를 것을 없앤다.
	 * 어느 플랫폼이든 먼저 올린 쪽에서 한 번만 열린다.
	 */
	private void openRewardsIfLaunched(String min) {
		if (min != null && AppVersion.compare(min, REWARD_LAUNCH_VERSION) >= 0) {
			rewardLaunchService.openIfNotYet(LocalDateTime.now(CrumbGrantService.ZONE));
		}
	}

	private void validate(String min, String latest) {
		if (min != null && !AppVersion.isValid(min)) {
			throw new AppVersionException(AppVersionErrorCode.INVALID_VERSION_FORMAT);
		}
		if (latest != null && !AppVersion.isValid(latest)) {
			throw new AppVersionException(AppVersionErrorCode.INVALID_VERSION_FORMAT);
		}
		// min > latest면 "최신 버전으로 올려도 막히는" 모순 — 어드민 실수를 여기서 잡는다.
		if (min != null && latest != null && AppVersion.compare(min, latest) > 0) {
			throw new AppVersionException(AppVersionErrorCode.MIN_ABOVE_LATEST);
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
