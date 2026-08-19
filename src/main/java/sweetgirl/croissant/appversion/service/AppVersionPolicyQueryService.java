package sweetgirl.croissant.appversion.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.appversion.dto.response.AppVersionPolicyResponse;
import sweetgirl.croissant.appversion.enums.AppPlatform;
import sweetgirl.croissant.appversion.repository.AppVersionPolicyRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AppVersionPolicyQueryService {

	private final AppVersionPolicyRepository appVersionPolicyRepository;

	/** 정책 행이 없어도 404가 아니라 빈 정책 — 앱은 "아무 것도 안 함"으로 읽는다. */
	public AppVersionPolicyResponse getPolicy(AppPlatform platform) {
		return appVersionPolicyRepository.findByPlatform(platform)
			.map(AppVersionPolicyResponse::from)
			.orElseGet(() -> AppVersionPolicyResponse.empty(platform));
	}
}
