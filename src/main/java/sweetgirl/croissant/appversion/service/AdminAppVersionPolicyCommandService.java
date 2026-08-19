package sweetgirl.croissant.appversion.service;

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

@Service
@RequiredArgsConstructor
@Transactional
public class AdminAppVersionPolicyCommandService {

	private final AppVersionPolicyRepository appVersionPolicyRepository;

	/** 플랫폼당 한 행 — 없으면 만들고 있으면 통째로 바꾼다. */
	public AppVersionPolicyResponse upsert(AppPlatform platform, AppVersionPolicyUpdateRequest request) {
		String min = blankToNull(request.minSupportedVersion());
		String latest = blankToNull(request.latestVersion());
		validate(min, latest);

		AppVersionPolicy policy = appVersionPolicyRepository.findByPlatform(platform)
			.orElseGet(() -> appVersionPolicyRepository.save(
				AppVersionPolicy.builder().platform(platform).build()));
		policy.update(min, latest, blankToNull(request.storeUrl()), blankToNull(request.message()));
		return AppVersionPolicyResponse.from(policy);
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
