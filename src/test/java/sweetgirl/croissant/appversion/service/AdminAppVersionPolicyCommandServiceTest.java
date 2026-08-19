package sweetgirl.croissant.appversion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.appversion.code.AppVersionErrorCode;
import sweetgirl.croissant.appversion.dto.request.AppVersionPolicyUpdateRequest;
import sweetgirl.croissant.appversion.dto.response.AppVersionPolicyResponse;
import sweetgirl.croissant.appversion.entity.AppVersionPolicy;
import sweetgirl.croissant.appversion.enums.AppPlatform;
import sweetgirl.croissant.appversion.exception.AppVersionException;
import sweetgirl.croissant.appversion.repository.AppVersionPolicyRepository;

@ExtendWith(MockitoExtension.class)
class AdminAppVersionPolicyCommandServiceTest {

	@Mock
	AppVersionPolicyRepository repository;

	@InjectMocks
	AdminAppVersionPolicyCommandService service;

	@Test
	@DisplayName("upsert: 기존 정책이 있으면 값을 통째로 바꾸고 빈 문자열은 null로 비운다")
	void upsert_updatesExisting_andBlankBecomesNull() {
		AppVersionPolicy existing = AppVersionPolicy.builder()
			.platform(AppPlatform.IOS).minSupportedVersion("1.2.0").latestVersion("1.3.0")
			.storeUrl("https://apps.apple.com/kr/app/id6792633374").message("old").build();
		given(repository.findByPlatform(AppPlatform.IOS)).willReturn(Optional.of(existing));

		AppVersionPolicyResponse res = service.upsert(AppPlatform.IOS,
			new AppVersionPolicyUpdateRequest(" ", "1.3.2", "https://apps.apple.com/kr/app/id6792633374", ""));

		assertThat(res.minSupportedVersion()).isNull();
		assertThat(res.latestVersion()).isEqualTo("1.3.2");
		assertThat(res.message()).isNull();
		assertThat(existing.getLatestVersion()).isEqualTo("1.3.2");
	}

	@Test
	@DisplayName("upsert: 정책 행이 없으면 만들어서 채운다")
	void upsert_createsWhenMissing() {
		given(repository.findByPlatform(AppPlatform.ANDROID)).willReturn(Optional.empty());
		given(repository.save(any(AppVersionPolicy.class))).willAnswer(inv -> inv.getArgument(0));

		AppVersionPolicyResponse res = service.upsert(AppPlatform.ANDROID,
			new AppVersionPolicyUpdateRequest(null, "1.3.1", null, null));

		verify(repository).save(any(AppVersionPolicy.class));
		assertThat(res.platform()).isEqualTo(AppPlatform.ANDROID);
		assertThat(res.latestVersion()).isEqualTo("1.3.1");
	}

	@Test
	@DisplayName("upsert: 1.2.3 형식이 아니면 거부한다")
	void upsert_rejectsBadFormat() {
		assertThatThrownBy(() -> service.upsert(AppPlatform.IOS,
			new AppVersionPolicyUpdateRequest("1.3", null, null, null)))
			.isInstanceOf(AppVersionException.class)
			.extracting(e -> ((AppVersionException)e).getErrorCode())
			.isEqualTo(AppVersionErrorCode.INVALID_VERSION_FORMAT);
	}

	@Test
	@DisplayName("upsert: 최소 지원 버전이 최신 버전보다 높으면 거부한다 — 올려도 막히는 모순")
	void upsert_rejectsMinAboveLatest() {
		assertThatThrownBy(() -> service.upsert(AppPlatform.IOS,
			new AppVersionPolicyUpdateRequest("1.4.0", "1.3.9", null, null)))
			.isInstanceOf(AppVersionException.class)
			.extracting(e -> ((AppVersionException)e).getErrorCode())
			.isEqualTo(AppVersionErrorCode.MIN_ABOVE_LATEST);
	}

	@Test
	@DisplayName("AppVersion.compare: 자리별 숫자 비교라 1.10.0 > 1.9.0")
	void compare_numericPerSegment() {
		assertThat(AppVersion.compare("1.10.0", "1.9.0")).isPositive();
		assertThat(AppVersion.compare("1.3.1", "1.3.1")).isZero();
		assertThat(AppVersion.compare("1.3.0", "1.3.1")).isNegative();
	}
}
