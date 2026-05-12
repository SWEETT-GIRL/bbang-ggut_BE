package sweetgirl.croissant.member.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.member.entity.OAuth;
import sweetgirl.croissant.member.enums.OAuthProvider;

public interface OAuthRepository extends JpaRepository<OAuth, Long> {

	// 카카오 로그인 시 기존 가입 여부를 확인하는 데 사용
	// - 결과 없음 → 신규 유저 → Member + OAuth 새로 저장
	// - 결과 있음 → 기존 유저 → 연결된 Member 반환
	Optional<OAuth> findByProviderAndProviderId(OAuthProvider provider, String providerId);

	boolean existsByProviderAndProviderId(OAuthProvider provider, String providerId);
}
