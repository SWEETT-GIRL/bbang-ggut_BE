package sweetgirl.croissant.appversion.dto.response;

import sweetgirl.croissant.appversion.entity.AppVersionPolicy;
import sweetgirl.croissant.appversion.enums.AppPlatform;

/** 앱이 자기 버전과 비교할 정책값. 정책이 없는 플랫폼은 전부 null — 앱은 아무 것도 하지 않는다. */
public record AppVersionPolicyResponse(
		AppPlatform platform,
		String minSupportedVersion,
		String latestVersion,
		String storeUrl,
		String message) {

	public static AppVersionPolicyResponse from(AppVersionPolicy policy) {
		return new AppVersionPolicyResponse(
			policy.getPlatform(),
			policy.getMinSupportedVersion(),
			policy.getLatestVersion(),
			policy.getStoreUrl(),
			policy.getMessage());
	}

	public static AppVersionPolicyResponse empty(AppPlatform platform) {
		return new AppVersionPolicyResponse(platform, null, null, null, null);
	}
}
