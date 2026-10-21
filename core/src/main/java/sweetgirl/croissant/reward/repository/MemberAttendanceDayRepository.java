package sweetgirl.croissant.reward.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.reward.entity.MemberAttendanceDay;
import sweetgirl.croissant.reward.entity.MemberDayId;

public interface MemberAttendanceDayRepository extends JpaRepository<MemberAttendanceDay, MemberDayId> {

	Optional<MemberAttendanceDay> findByMemberIdAndDay(Long memberId, LocalDate day);

	/**
	 * 그날의 출석을 남긴다. 남겼으면 1, 그날 이미 했으면 0 이다.
	 *
	 * <p>앱이 두 번 누르거나 요청이 나란히 들어와도 PK 가 한 줄만 받는다.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO member_attendance_days (attended_on, member_id, board_day, crumbs, created_at)
			VALUES (:day, :memberId, :boardDay, :crumbs, :now)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(
			@Param("memberId") Long memberId,
			@Param("day") LocalDate day,
			@Param("boardDay") int boardDay,
			@Param("crumbs") int crumbs,
			@Param("now") LocalDateTime now);

	/** 실제로 받은 양을 고쳐 적는다(제재 중이라 못 받았을 때 0 으로). */
	@Modifying
	@Query(value = """
			UPDATE member_attendance_days SET crumbs = :crumbs
			WHERE attended_on = :day AND member_id = :memberId
			""", nativeQuery = true)
	int updateCrumbs(@Param("memberId") Long memberId, @Param("day") LocalDate day, @Param("crumbs") int crumbs);
}
