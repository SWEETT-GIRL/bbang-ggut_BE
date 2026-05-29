package sweetgirl.croissant.alarm.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.member.entity.Member;

public interface FcmTokenRepository extends JpaRepository<FcmToken, Long> {

	Optional<FcmToken> findByMemberAndDeviceId(Member member, String deviceId);

	List<FcmToken> findByMemberAndIsActive(Member member, boolean isActive);

	boolean existsByMemberAndDeviceId(Member member, String deviceId);

	List<FcmToken> findAllByToken(String token);

	List<FcmToken> findAllByTokenAndIsActiveTrue(String token);

	// 회원 탈퇴 시 해당 회원의 모든 FCM 토큰 삭제를 위해 사용
	void deleteAllByMember(Member member);
}
