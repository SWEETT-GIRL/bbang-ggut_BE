package sweetgirl.croissant.reward.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.reward.entity.MemberAttendanceDay;
import sweetgirl.croissant.reward.entity.MemberDayId;

/** 리워드 푸시를 받을 사람을 고르는 조회. 알림 수신 설정은 여기서 보지 않는다 — 내보내는 쪽({@code PushDispatcher})이 본다. */
public interface RewardPushTargetRepository extends Repository<MemberAttendanceDay, MemberDayId> {

	/**
	 * 그 뒤로 앱을 쓴 적이 있는 회원들(게스트 · 탈퇴 제외).
	 * 앱은 쓰는 동안 토큰을 새로 받으므로, 최근에 발급된 토큰이 있으면 최근에 들어온 것이다.
	 */
	@Query(value = """
			SELECT DISTINCT member.id
			FROM member
			JOIN refresh_token token ON token.member_id = member.id
			WHERE member.deleted_at IS NULL
				AND member.member_type <> 'GUEST'
				AND token.created_at >= :since
			ORDER BY member.id
			""", nativeQuery = true)
	List<Long> findMemberIdsActiveSince(@Param("since") LocalDateTime since);

	/** 다른 인스턴스가 같은 발송을 돌리고 있으면 false. 잠금은 트랜잭션이 끝나면 풀린다. */
	@Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
	boolean tryAdvisoryXactLock(@Param("key") long key);

	/**
	 * 어제 출석했고 오늘은 아직 안 한 회원과, 어제 찍은 칸(게스트 · 탈퇴 제외).
	 * 어제 출석하지 않은 회원은 이미 끊긴 것이라 부르지 않는다.
	 */
	@Query(value = """
			SELECT yesterday.member_id AS memberId, yesterday.board_day AS boardDay
			FROM member_attendance_days yesterday
			JOIN member ON member.id = yesterday.member_id
			WHERE yesterday.attended_on = :yesterday
				AND member.deleted_at IS NULL
				AND member.member_type <> 'GUEST'
				AND NOT EXISTS (
					SELECT 1 FROM member_attendance_days today
					WHERE today.attended_on = :today AND today.member_id = yesterday.member_id)
			ORDER BY yesterday.member_id
			""", nativeQuery = true)
	List<AttendanceReminderTarget> findAttendanceReminderTargets(
			@Param("yesterday") LocalDate yesterday, @Param("today") LocalDate today);

	interface AttendanceReminderTarget {

		Long getMemberId();

		Integer getBoardDay();
	}

	/**
	 * 어제 가입했고 아직 한 번도 출석하지 않은 회원과, 그 회원이 고른 친구와 그 친구에게 지어 준 이름(게스트 · 탈퇴 제외).
	 * 세계관을 아직 시작하지 않았으면 친구가 비어 있다.
	 */
	@Query(value = """
			SELECT member.id AS memberId, world.selected_character AS selectedCharacter,
				friend.nickname AS friendNickname
			FROM member
			LEFT JOIN member_world world ON world.member_id = member.id
			LEFT JOIN member_characters friend
				ON friend.member_id = member.id AND friend.character_code = world.selected_character
			WHERE member.deleted_at IS NULL
				AND member.member_type <> 'GUEST'
				AND member.registered_at >= :from
				AND member.registered_at < :to
				AND NOT EXISTS (
					SELECT 1 FROM member_attendance_days day WHERE day.member_id = member.id)
			ORDER BY member.id
			""", nativeQuery = true)
	List<MemberFriend> findSecondMorningTargets(
			@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

	/** 이 회원들이 고른 친구와 그 친구에게 지어 준 이름. 세계관을 아직 시작하지 않은 회원은 빠진다. */
	@Query(value = """
			SELECT world.member_id AS memberId, world.selected_character AS selectedCharacter,
				friend.nickname AS friendNickname
			FROM member_world world
			LEFT JOIN member_characters friend
				ON friend.member_id = world.member_id AND friend.character_code = world.selected_character
			WHERE world.member_id IN (:memberIds)
			""", nativeQuery = true)
	List<MemberFriend> findFriendsOf(@Param("memberIds") Collection<Long> memberIds);

	/** 회원이 고른 친구. 이름을 지어 주지 않았으면 {@code friendNickname} 이 비어 있다. */
	interface MemberFriend {

		Long getMemberId();

		String getSelectedCharacter();

		String getFriendNickname();
	}
}
