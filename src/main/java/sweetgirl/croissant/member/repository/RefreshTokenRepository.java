package sweetgirl.croissant.member.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	// token 값으로 RefreshToken 레코드 조회
	List<RefreshToken> findAllByToken(String token);

	// 특정 Member + deviceId 조합으로 RefreshToken 조회, 기존 토큰을 찾아서 새 토큰으로 교체(덮어쓰기)할 때 사용
	Optional<RefreshToken> findByMemberAndDeviceId(Member member, String deviceId);

	// 회원 탈퇴 시 해당 회원의 모든 RefreshToken revoke를 위해 사용
	List<RefreshToken> findAllByMember(Member member);
}
