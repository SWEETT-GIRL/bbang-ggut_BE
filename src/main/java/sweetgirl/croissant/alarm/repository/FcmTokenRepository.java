package sweetgirl.croissant.alarm.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.member.entity.Member;

public interface FcmTokenRepository extends JpaRepository<FcmToken, Long> {

	List<FcmToken> findByMemberAndIsActive(Member member, boolean isActive);

	List<FcmToken> findAllByToken(String token);

	List<FcmToken> findAllByTokenAndIsActiveTrue(String token);

	/** 이 회원에게 {@code token} 말고 살아 있는 토큰이 또 있는가 — APNs 환경이 안 맞는 토큰을 버려도 되는지 볼 때 */
	boolean existsByMemberAndIsActiveTrueAndTokenNot(Member member, String token);

	// 회원 탈퇴 시 해당 회원의 모든 FCM 토큰 삭제를 위해 사용
	void deleteAllByMember(Member member);

	/** 보관 기한이 지난 탈퇴 회원에 남은 푸시 토큰을 지운다(탈퇴 때 삭제가 빠진 옛 행 정리). */
	@Modifying
	@Query("DELETE FROM FcmToken f WHERE f.member.id IN (SELECT m.id FROM Member m WHERE m.deletedAt < :cutoff)")
	int deleteAllOfMembersDeletedBefore(@Param("cutoff") LocalDateTime cutoff);

	/**
	 * 기기 한 대의 토큰을 한 문장으로 넣거나 갱신한다.
	 *
	 * <p>같은 (회원, 기기)의 등록이 동시에 둘 들어온다 — 앱이 첫 기동과 토큰 갱신 리스너에서 잇따라 부른다.
	 * 따로 읽고 넣으면 양쪽 다 "없다"를 보고 넣으려 들고, 뒤에 온 쪽은 유니크로 깨진다. 한 문장이면 뒤에 온 쪽이
	 * 앞 쪽의 커밋을 기다렸다가 갱신으로 돈다. 기기의 OS 는 바뀌지 않으니 갱신하지 않는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			INSERT INTO fcm_token (member_id, device_id, device_os, token, app_version, build_version, bundle_id,
				is_active, created_at, updated_at)
			VALUES (:memberId, :deviceId, :deviceOs, :token, :appVersion, :buildVersion, :bundleId,
				TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			ON CONFLICT (member_id, device_id) DO UPDATE SET
				token = EXCLUDED.token,
				app_version = EXCLUDED.app_version,
				build_version = EXCLUDED.build_version,
				bundle_id = EXCLUDED.bundle_id,
				is_active = TRUE,
				updated_at = CURRENT_TIMESTAMP
			""", nativeQuery = true)
	int upsert(
			@Param("memberId") Long memberId,
			@Param("deviceId") String deviceId,
			@Param("deviceOs") String deviceOs,
			@Param("token") String token,
			@Param("appVersion") String appVersion,
			@Param("buildVersion") String buildVersion,
			@Param("bundleId") String bundleId);
}
