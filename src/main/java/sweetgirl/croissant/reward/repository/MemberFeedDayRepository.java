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

	/** 그날의 밥 룰렛 결과를 남긴다. 남겼으면 1, 그날 이미 돌렸으면 0 이다. */
	@Modifying
	@Query(value = """
			INSERT INTO member_feed_days (member_id, fed_on, wedge, crumbs, created_at)
			VALUES (:memberId, :day, :wedge, :crumbs, :now)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(
			@Param("memberId") Long memberId,
			@Param("day") LocalDate day,
			@Param("wedge") int wedge,
			@Param("crumbs") int crumbs,
			@Param("now") LocalDateTime now);

	/** 실제로 받은 양을 고쳐 적는다(제재 중이라 못 받았을 때 0 으로). */
	@Modifying
	@Query(value = """
			UPDATE member_feed_days SET crumbs = :crumbs
			WHERE member_id = :memberId AND fed_on = :day
			""", nativeQuery = true)
	int updateCrumbs(@Param("memberId") Long memberId, @Param("day") LocalDate day, @Param("crumbs") int crumbs);
}
