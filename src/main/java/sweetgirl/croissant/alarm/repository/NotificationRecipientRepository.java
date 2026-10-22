package sweetgirl.croissant.alarm.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.alarm.entity.NotificationRecipient;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;

/**
 * 알림함이 보는 테이블. 목록·미읽음·읽음 처리가 전부 여기서 끝난다.
 *
 * <p>목록 조회는 내용까지 함께 끌어온다({@code JOIN FETCH}). 우편함 한 줄만 읽어봐야 무엇을
 * 알리는 줄인지 모르고, 안 붙이면 한 화면에 알림 수만큼 쿼리가 더 나간다.
 *
 * <p>지운 코스를 가리키는 알림도 그대로 보여준다. 예전에는 Course를 조건부 조인해 조회에서
 * 숨겼는데, 그러면 "분명히 봤는데 알림이 사라졌다"가 된다. 탭했을 때 앱이 "삭제된 빵투어예요"를
 * 띄우는 쪽으로 바꿨다. 미읽음 개수도 같은 이유로 거르지 않는다 — 목록과 배지가 어긋나면 그게
 * 더 나쁘다.
 */
public interface NotificationRecipientRepository extends JpaRepository<NotificationRecipient, Long> {

	@Query("""
			SELECT r FROM NotificationRecipient r
			JOIN FETCH r.notification n
			LEFT JOIN FETCH n.actor
			WHERE r.member.id = :memberId
			ORDER BY r.notifiedAt DESC
			""")
	Slice<NotificationRecipient> findMine(@Param("memberId") Long memberId, Pageable pageable);

	/** 알림함 묶음의 대표 id 로 읽음이 오면 같은 날 · 종류 · 대상의 나머지도 읽음 처리. */
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE NotificationRecipient r SET r.readAt = :now
			WHERE r.member.id = :memberId AND r.readAt IS NULL
				AND r.notifiedAt >= :from AND r.notifiedAt < :to
				AND r.notification.id IN (SELECT n.id FROM Notification n
					WHERE n.type = :type AND n.targetType = :targetType AND n.targetId = :targetId)
			""")
	int markGroupAsRead(@Param("memberId") Long memberId, @Param("type") NotificationType type,
			@Param("targetType") NotificationTargetType targetType, @Param("targetId") Long targetId,
			@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("now") LocalDateTime now);

	/** 구버전 앱이 쓰는 경로 — 저장 알림만 골라 준다. */
	@Query("""
			SELECT r FROM NotificationRecipient r
			JOIN FETCH r.notification n
			LEFT JOIN FETCH n.actor
			WHERE r.member.id = :memberId AND n.type = :type
			ORDER BY r.notifiedAt DESC
			""")
	Slice<NotificationRecipient> findMineByType(@Param("memberId") Long memberId,
			@Param("type") NotificationType type, Pageable pageable);

	/** 제보 결과만 보는 화면용. 타입 몇 개를 골라 최신순으로 준다. */
	@Query("""
			SELECT r FROM NotificationRecipient r
			JOIN FETCH r.notification n
			WHERE r.member.id = :memberId AND n.type IN :types
			ORDER BY r.notifiedAt DESC
			""")
	List<NotificationRecipient> findMineByTypes(@Param("memberId") Long memberId,
			@Param("types") List<NotificationType> types);

	@Query("""
			SELECT COUNT(r) FROM NotificationRecipient r
			WHERE r.member.id = :memberId AND r.readAt IS NULL
			""")
	long countUnread(@Param("memberId") Long memberId);

	@Query("""
			SELECT COUNT(r) FROM NotificationRecipient r
			WHERE r.member.id = :memberId AND r.readAt IS NULL AND r.notification.type = :type
			""")
	long countUnreadByType(@Param("memberId") Long memberId, @Param("type") NotificationType type);

	List<NotificationRecipient> findByMemberIdAndIdIn(Long memberId, List<Long> ids);

	/** 활동 알림의 재알림용 — 이미 만들어둔 우편함 줄을 집어 시각만 되돌린다. */
	Optional<NotificationRecipient> findByNotificationIdAndMemberId(Long notificationId, Long memberId);

	/**
	 * 전체 읽음. 한 줄씩 올려 markAsRead를 부르면 회원이 쌓아둔 알림 수만큼 UPDATE가 나간다.
	 * 넣을 값이 readAt 하나뿐이라 DB 안에서 끝내는 게 맞다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE NotificationRecipient r
			SET r.readAt = :now
			WHERE r.member.id = :memberId AND r.readAt IS NULL
			""")
	int markAllAsRead(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);

	/**
	 * 구버전 앱의 전체 읽음. 그 앱이 보지도 못하는 공지까지 읽음으로 만들면,
	 * 나중에 업데이트했을 때 못 본 공지가 이미 읽은 것으로 남는다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE NotificationRecipient r
			SET r.readAt = :now
			WHERE r.member.id = :memberId AND r.readAt IS NULL
				AND r.notification IN (SELECT n FROM Notification n WHERE n.type = :type)
			""")
	int markAllAsReadByType(@Param("memberId") Long memberId,
			@Param("type") NotificationType type, @Param("now") LocalDateTime now);

	/**
	 * 공지를 전 회원의 우편함에 한 줄씩. 한 문장으로 끝낸다.
	 *
	 * <p>회원을 전부 엔티티로 올려 saveAll 하면 만 명이면 만 개를 메모리에 얹는다. 넣을 값이
	 * 사람마다 member_id 하나뿐이라 DB 안에서 처리하는 게 맞다.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO notification_recipient
				(notification_id, member_id, notified_at, created_at)
			SELECT :notificationId, m.id, :now, :now
			FROM member m
			WHERE m.deleted_at IS NULL
			""", nativeQuery = true)
	int insertForAllMembers(@Param("notificationId") Long notificationId,
			@Param("now") LocalDateTime now);

	/**
	 * 공지 팬아웃용 — 이 알림을 받은 사람들을 쪽씩 끊어 읽는다.
	 * 회원을 함께 끌어오는 이유는 발송 자격 판정과 토큰 조회가 곧바로 회원을 필요로 해서다.
	 */
	@Query(value = """
			SELECT r FROM NotificationRecipient r
			JOIN FETCH r.member
			WHERE r.notification.id = :notificationId
			ORDER BY r.id
			""", countQuery = "SELECT COUNT(r) FROM NotificationRecipient r WHERE r.notification.id = :notificationId")
	Slice<NotificationRecipient> findByNotificationId(@Param("notificationId") Long notificationId,
			Pageable pageable);
}
