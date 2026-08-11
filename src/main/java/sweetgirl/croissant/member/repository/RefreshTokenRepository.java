package sweetgirl.croissant.member.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

	// 같은 기기의 게스트 발급을 직렬화하는 트랜잭션 advisory lock(커밋/롤백 때 자동 해제).
	// 위 findGuestTokensByDeviceId로 "기존 게스트 없음"을 확인하고 새로 만들기까지가 원자적이어야
	// 쌍둥이 게스트가 안 생긴다. 기기 단위 키라 다른 기기끼리는 서로 막지 않는다.
	// pg_advisory_xact_lock의 반환 타입은 void라 매핑이 애매하니, 상수 1을 감싸서 뽑는다.
	@Query(value = "select 1 from (select pg_advisory_xact_lock(:lockKey)) as guest_device_lock", nativeQuery = true)
	Integer lockGuestDevice(@Param("lockKey") long lockKey);

	// 같은 기기의 기존 토큰 정리 — 새 토큰 저장 직전에 호출한다.
	// 엔티티를 읽어서 delete하면 안 된다: 같은 deviceId로 로그인이 겹치거나 게스트 승격이 끼어들어
	// 다른 트랜잭션이 먼저 그 행을 지우면, flush 시 row count가 0이라
	// StaleStateException(expected row count 1 but was 0)으로 500이 난다.
	// 벌크 삭제는 대상이 0건이든 여러 건이든 예외 없이 지나간다.
	@Modifying(flushAutomatically = true)
	@Query("delete from RefreshToken rt where rt.member.id = :memberId and rt.deviceId = :deviceId")
	void deleteByMemberIdAndDeviceId(@Param("memberId") Long memberId, @Param("deviceId") String deviceId);

	// 회원 탈퇴 시 해당 회원의 모든 RefreshToken revoke를 위해 사용
	List<RefreshToken> findAllByMember(Member member);
}
