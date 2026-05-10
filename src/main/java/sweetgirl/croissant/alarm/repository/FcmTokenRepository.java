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

	Optional<FcmToken> findByToken(String token);
}
