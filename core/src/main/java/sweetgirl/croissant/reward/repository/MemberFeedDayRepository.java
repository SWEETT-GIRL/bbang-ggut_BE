package sweetgirl.croissant.reward.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.reward.entity.MemberDayId;
import sweetgirl.croissant.reward.entity.MemberFeedDay;

public interface MemberFeedDayRepository extends JpaRepository<MemberFeedDay, MemberDayId> {

	Optional<MemberFeedDay> findByMemberIdAndDay(Long memberId, LocalDate day);

	/**
	 * 그날의 밥 룰렛 결과를 남긴다. 남겼으면 1, 그날 이미 돌렸으면 0 이다.
	 *
	 * @param extraLeft 광고를 보고 더 돌릴 수 있는 횟수의 시작값(하루 상한)
	 */
	@Modifying
	@Query(value = """
			INSERT INTO member_feed_days
				(member_id, fed_on, wedge, crumbs, feed_extra_left, feed_extra_ready, created_at)
			VALUES (:memberId, :day, :wedge, :crumbs, :extraLeft, FALSE, :now)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(
			@Param("memberId") Long memberId,
			@Param("day") LocalDate day,
			@Param("wedge") int wedge,
			@Param("crumbs") int crumbs,
			@Param("extraLeft") int extraLeft,
			@Param("now") LocalDateTime now);

	/** 실제로 받은 양을 고쳐 적는다(제재 중이라 못 받았을 때 0 으로). */
	@Modifying
	@Query(value = """
			UPDATE member_feed_days SET crumbs = :crumbs
			WHERE member_id = :memberId AND fed_on = :day
			""", nativeQuery = true)
	int updateCrumbs(@Param("memberId") Long memberId, @Param("day") LocalDate day, @Param("crumbs") int crumbs);

	/**
	 * 광고 한 편으로 한 판을 연다. 열었으면 1 이다. 그날 무료 판을 아직 안 돌렸거나(행이 없다), 횟수를 다 썼거나,
	 * 이미 열린 판이 있으면 0 이다 — 조건을 WHERE 에 두어 같은 콜백이 나란히 와도 한 번만 연다.
	 */
	@Modifying
	@Query(value = """
			UPDATE member_feed_days SET feed_extra_ready = TRUE, feed_extra_left = feed_extra_left - 1
			WHERE member_id = :memberId AND fed_on = :day AND feed_extra_left > 0 AND feed_extra_ready = FALSE
			""", nativeQuery = true)
	int openExtraIfLeft(@Param("memberId") Long memberId, @Param("day") LocalDate day);

	/** 열려 있던 판을 쓴다. 썼으면 1, 열린 판이 없었으면 0 이다 — 두 번 눌러도 한 판만 돈다. */
	@Modifying
	@Query(value = """
			UPDATE member_feed_days SET feed_extra_ready = FALSE
			WHERE member_id = :memberId AND fed_on = :day AND feed_extra_ready = TRUE
			""", nativeQuery = true)
	int closeExtraIfReady(@Param("memberId") Long memberId, @Param("day") LocalDate day);
}
