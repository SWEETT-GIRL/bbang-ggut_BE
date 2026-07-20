package sweetgirl.croissant.member.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	List<RefreshToken> findAllByToken(String token);

	// 게스트 재사용: 같은 기기(deviceId)에 이미 발급된 게스트 멤버를 찾아 재사용한다.
	// IDFV 기반 deviceId는 앱 재설치에도 유지되므로, 재설치/토큰 만료 후에도 신원(=코스 소유권)이 유지된다.
	// 소셜(USER) 멤버 오인 방지를 위해 memberType='GUEST'이고 미탈퇴인 것만, 최신순으로.
	@Query("select rt from RefreshToken rt "
		+ "where rt.deviceId = :deviceId and rt.member.memberType = 'GUEST' and rt.member.deletedAt is null "
		+ "order by rt.id desc")
	List<RefreshToken> findGuestTokensByDeviceId(@Param("deviceId") String deviceId);

	// 특정 Member + deviceId 조합으로 RefreshToken 조회, 기존 토큰을 찾아서 새 토큰으로 교체(덮어쓰기)할 때 사용
	Optional<RefreshToken> findByMemberAndDeviceId(Member member, String deviceId);

	// 회원 탈퇴 시 해당 회원의 모든 RefreshToken revoke를 위해 사용
	List<RefreshToken> findAllByMember(Member member);
}
