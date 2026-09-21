package sweetgirl.croissant.personalization.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;

public interface UserTasteProfileRepository extends JpaRepository<UserTasteProfile, Long> {

	/**
	 * 없으면 빈 행(축·타입 없음, version 1)을 만든다. 있으면 아무것도 안 한다. 검사 제출과 배치가 같은 회원의 첫 행을 동시에 만들
	 * 때 — JPA 는 flush 중 PK 충돌이 나면 트랜잭션이 rollback-only 가 되어 재시도가 안 되므로, 충돌이 나지 않는 INSERT 를 먼저 하고
	 * {@link #findByIdForUpdate} 로 잠근다(#732 W3).
	 */
	@Modifying
	@Query(value = """
			INSERT INTO user_taste_profile (member_id, computed_at)
			VALUES (:memberId, :computedAt)
			ON CONFLICT (member_id) DO NOTHING
			""", nativeQuery = true)
	int insertEmptyIfAbsent(@Param("memberId") Long memberId, @Param("computedAt") LocalDateTime computedAt);

	/** SELECT … FOR UPDATE — 검사 제출과 배치 재계산이 같은 행을 동시에 쓰지 않게 줄 세운다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT profile FROM UserTasteProfile profile WHERE profile.memberId = :memberId")
	Optional<UserTasteProfile> findByIdForUpdate(@Param("memberId") Long memberId);
}
