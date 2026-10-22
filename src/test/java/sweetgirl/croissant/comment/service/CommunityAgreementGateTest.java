package sweetgirl.croissant.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.appversion.entity.AppVersionPolicy;
import sweetgirl.croissant.appversion.enums.AppPlatform;
import sweetgirl.croissant.appversion.repository.AppVersionPolicyRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityAgreementGate")
class CommunityAgreementGateTest {

	@Mock
	private AppVersionPolicyRepository appVersionPolicyRepository;

	@InjectMocks
	private CommunityAgreementGate gate;

	private Optional<AppVersionPolicy> policy(String minSupportedVersion) {
		AppVersionPolicy policy = mock(AppVersionPolicy.class);
		given(policy.getMinSupportedVersion()).willReturn(minSupportedVersion);
		return Optional.of(policy);
	}

	@Test
	@DisplayName("정책이 없으면 옛 앱이 남아 있는 것으로 보고 묻지 않는다")
	void noPolicy() {
		given(appVersionPolicyRepository.findByPlatform(AppPlatform.IOS)).willReturn(Optional.empty());

		assertThat(gate.requiresAgreementForPublishing()).isFalse();
	}

	@Test
	@DisplayName("한 플랫폼이라도 최소 지원 버전이 2.1.0 미만이면 묻지 않는다")
	void onePlatformBehind() {
		Optional<AppVersionPolicy> ios = policy("2.1.0");
		Optional<AppVersionPolicy> android = policy("2.0.2");
		given(appVersionPolicyRepository.findByPlatform(AppPlatform.IOS)).willReturn(ios);
		given(appVersionPolicyRepository.findByPlatform(AppPlatform.ANDROID)).willReturn(android);

		assertThat(gate.requiresAgreementForPublishing()).isFalse();
	}

	@Test
	@DisplayName("두 플랫폼 다 2.1.0 이상으로 강제 업데이트했으면 묻는다")
	void bothForcedToCommunityVersion() {
		Optional<AppVersionPolicy> ios = policy("2.1.0");
		Optional<AppVersionPolicy> android = policy("2.1.3");
		given(appVersionPolicyRepository.findByPlatform(AppPlatform.IOS)).willReturn(ios);
		given(appVersionPolicyRepository.findByPlatform(AppPlatform.ANDROID)).willReturn(android);

		assertThat(gate.requiresAgreementForPublishing()).isTrue();
	}
}
