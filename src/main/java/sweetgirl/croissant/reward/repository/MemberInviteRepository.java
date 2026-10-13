package sweetgirl.croissant.reward.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.reward.entity.MemberInvite;

public interface MemberInviteRepository extends JpaRepository<MemberInvite, Long> {

	Optional<MemberInvite> findByInviteeId(Long inviteeId);

	boolean existsByInviteeId(Long inviteeId);

	boolean existsByInviterIdAndInviteeId(Long inviterId, Long inviteeId);

	List<MemberInvite> findAllByInviterIdOrderByIdAsc(Long inviterId);

	long countByInviterIdAndRewardedAtIsNotNull(Long inviterId);

	/** 코드를 넣은 사실을 남긴다. 남겼으면 1, 이미 다른 코드를 넣은 사람이면 0 이다. */
	@Modifying
	@Query(value = """
			INSERT INTO member_invites (inviter_id, invitee_id, created_at)
			VALUES (:inviterId, :inviteeId, :now)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(
			@Param("inviterId") Long inviterId,
			@Param("inviteeId") Long inviteeId,
			@Param("now") LocalDateTime now);

	/** 초대를 성사시킨다. 성사시켰으면 1, 이미 성사됐으면 0 이다 — 첫 기록이 나란히 들어와도 한 번만 준다. */
	@Modifying
	@Query("""
			UPDATE MemberInvite invite SET invite.rewardedAt = :now
			WHERE invite.id = :id AND invite.rewardedAt IS NULL
			""")
	int markRewarded(@Param("id") Long id, @Param("now") LocalDateTime now);
}
