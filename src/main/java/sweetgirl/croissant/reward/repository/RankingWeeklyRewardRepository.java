package sweetgirl.croissant.reward.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.reward.entity.RankingWeeklyReward;

public interface RankingWeeklyRewardRepository extends JpaRepository<RankingWeeklyReward, Long> {

	Optional<RankingWeeklyReward> findByIdAndMemberId(Long id, Long memberId);

	/** 아직 안 받았고 기한이 남은 것 — 최근 주부터. */
	@Query("""
			SELECT reward FROM RankingWeeklyReward reward
			WHERE reward.memberId = :memberId AND reward.claimedAt IS NULL AND reward.weekStart >= :earliestWeek
			ORDER BY reward.weekStart DESC
			""")
	List<RankingWeeklyReward> findClaimable(
			@Param("memberId") Long memberId, @Param("earliestWeek") LocalDate earliestWeek);

	/** 지금 왕관을 쓰고 있는 회원들(지난주 1 위). */
	@Query("""
			SELECT reward.memberId FROM RankingWeeklyReward reward
			WHERE reward.finalRank = 1 AND reward.crownUntil >= :today AND reward.weekStart < :today
			""")
	List<Long> findCrownedMemberIds(@Param("today") LocalDate today);

	/** 그 주의 보상 줄을 남긴다. 남겼으면 1, 이미 있으면 0 이다 — 스케줄러가 두 번 돌거나 두 인스턴스에서 돌아도 한 줄이다. */
	@Modifying
	@Query(value = """
			INSERT INTO ranking_weekly_rewards
				(week_start, member_id, final_rank, crumbs, crown_until, score, created_at)
			VALUES (:weekStart, :memberId, :finalRank, :crumbs, :crownUntil, :score, :now)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(
			@Param("weekStart") LocalDate weekStart,
			@Param("memberId") Long memberId,
			@Param("finalRank") int finalRank,
			@Param("crumbs") int crumbs,
			@Param("crownUntil") LocalDate crownUntil,
			@Param("score") Integer score,
			@Param("now") LocalDateTime now);

	/** 받는다. 받았으면 1, 이미 받았거나 기한이 지났으면 0 이다. */
	@Modifying
	@Query("""
			UPDATE RankingWeeklyReward reward SET reward.claimedAt = :now
			WHERE reward.id = :id AND reward.memberId = :memberId
			AND reward.claimedAt IS NULL AND reward.weekStart >= :earliestWeek
			""")
	int claim(
			@Param("id") Long id,
			@Param("memberId") Long memberId,
			@Param("earliestWeek") LocalDate earliestWeek,
			@Param("now") LocalDateTime now);
}
