package sweetgirl.croissant.comment.service;

import java.util.Arrays;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.appversion.entity.AppVersionPolicy;
import sweetgirl.croissant.appversion.enums.AppPlatform;
import sweetgirl.croissant.appversion.repository.AppVersionPolicyRepository;
import sweetgirl.croissant.appversion.service.AppVersion;

/**
 * 빵로그 공개 전환에 커뮤니티 규칙 동의를 요구할지 — 강제 업데이트 정책과 묶여 있다.
 *
 * <p>규칙 동의 시트는 {@value #COMMUNITY_APP_VERSION} 앱부터 있다. 그 전 앱이 아직 돌아가는 동안 서버가 동의를 요구하면
 * 옛 앱은 공개 버튼이 403 으로 막힐 뿐 동의할 길이 없다. 그래서 어드민이 두 플랫폼 모두 최소 지원 버전을
 * {@value #COMMUNITY_APP_VERSION} 이상으로 올린(강제 업데이트) 뒤에야 요구한다 — 출시 때 따로 켤 스위치가 없다.
 * 댓글 쓰기는 옛 앱에 없으므로 늘 요구한다.
 */
@Component
@RequiredArgsConstructor
public class CommunityAgreementGate {

	static final String COMMUNITY_APP_VERSION = "2.1.0";

	private final AppVersionPolicyRepository appVersionPolicyRepository;

	/** 두 플랫폼 다 정책이 있고 최소 지원 버전이 커뮤니티 앱 버전 이상이면 참. */
	public boolean requiresAgreementForPublishing() {
		return Arrays.stream(AppPlatform.values())
			.allMatch(platform -> appVersionPolicyRepository.findByPlatform(platform)
				.map(AppVersionPolicy::getMinSupportedVersion)
				.filter(AppVersion::isValid)
				.map(version -> AppVersion.compare(version, COMMUNITY_APP_VERSION) >= 0)
				.orElse(false));
	}
}
