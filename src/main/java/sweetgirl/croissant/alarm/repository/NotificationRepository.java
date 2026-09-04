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

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.course.entity.Course;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	/**
	 * 내 알림 목록 — 최신순.
	 *
	 * <p>actor를 LEFT JOIN FETCH로 같이 끌어온다. 응답에 닉네임이 들어가는데 LAZY로 두면 20줄에
	 * 20번이 더 나가고, 공지처럼 actor가 없는 줄도 있어서 INNER가 아니라 LEFT여야 한다.
	 *
	 * <p><b>지운 코스의 알림은 뺀다.</b> 줄을 누르면 그 코스로 보내는데 이미 지웠으면 상세가
	 * 404로 떨어진다 — 눌러도 아무 데도 못 가는 줄을 남겨둘 이유가 없다. 행 자체를 지우지는
	 * 않는다. 저장은 실제로 일어났던 일이다. 대상을 관계로 안 묶었으므로 Course를 조건부로
	 * 조인해 거른다. 코스가 아닌 알림(공지 등)은 조인이 비어 그대로 통과한다.
	 */
	@Query("""
			SELECT n FROM Notification n
			LEFT JOIN FETCH n.actor
			LEFT JOIN Course c ON c.id = n.targetId AND n.targetType = 'COURSE'
			WHERE n.recipient.id = :recipientId
				AND (c.id IS NULL OR c.deletedAt IS NULL)
			ORDER BY n.notifiedAt DESC
			""")
	Slice<Notification> findMine(@Param("recipientId") Long recipientId, Pageable pageable);

	/** 구버전 앱이 쓰는 경로 — 저장 알림만 골라 준다. 지운 코스 제외 규칙은 위와 같다. */
	@Query("""
			SELECT n FROM Notification n
			LEFT JOIN FETCH n.actor
			LEFT JOIN Course c ON c.id = n.targetId AND n.targetType = 'COURSE'
			WHERE n.recipient.id = :recipientId AND n.type = :type
				AND (c.id IS NULL OR c.deletedAt IS NULL)
			ORDER BY n.notifiedAt DESC
			""")
	Slice<Notification> findMineByType(@Param("recipientId") Long recipientId,
			@Param("type") NotificationType type, Pageable pageable);

	@Query("""
			SELECT COUNT(n) FROM Notification n
			LEFT JOIN Course c ON c.id = n.targetId AND n.targetType = 'COURSE'
			WHERE n.recipient.id = :recipientId AND n.readAt IS NULL
				AND (c.id IS NULL OR c.deletedAt IS NULL)
			""")
	long countUnread(@Param("recipientId") Long recipientId);

	@Query("""
			SELECT COUNT(n) FROM Notification n
			LEFT JOIN Course c ON c.id = n.targetId AND n.targetType = 'COURSE'
			WHERE n.recipient.id = :recipientId AND n.type = :type AND n.readAt IS NULL
				AND (c.id IS NULL OR c.deletedAt IS NULL)
			""")
	long countUnreadByType(@Param("recipientId") Long recipientId,
			@Param("type") NotificationType type);

	List<Notification> findByRecipientIdAndIdIn(Long recipientId, List<Long> ids);

	/** 같은 사람이 같은 대상에 같은 행동을 다시 했는지 — 중복 대신 갱신하려고 먼저 찾는다. */
	Optional<Notification> findByActorIdAndTypeAndTargetTypeAndTargetId(Long actorId,
			NotificationType type, NotificationTargetType targetType, Long targetId);

	/**
	 * 전체 읽음. 한 줄씩 불러 고치면 알림이 쌓인 사람에게 그만큼 UPDATE가 나간다.
	 * 벌크 UPDATE는 영속성 컨텍스트를 안 거치므로 호출부에서 이후 조회를 하지 않는다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE Notification n
			SET n.readAt = :now
			WHERE n.recipient.id = :recipientId AND n.readAt IS NULL
			""")
	int markAllAsRead(@Param("recipientId") Long recipientId, @Param("now") LocalDateTime now);

	/**
	 * 공지를 전 회원에게 한 줄씩. 한 문장으로 끝낸다.
	 *
	 * <p>회원을 전부 엔티티로 올려 saveAll 하면 만 명이면 만 개를 메모리에 얹는다. 넣을 값이
	 * 사람마다 recipient_id 하나뿐이라 DB 안에서 처리하는 게 맞다.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO notification
				(recipient_id, type, title, body, link, notified_at, created_at)
			SELECT m.id, 'NOTICE', :title, :body, :link, :now, :now
			FROM member m
			WHERE m.deleted_at IS NULL
			""", nativeQuery = true)
	int insertNoticeForAllMembers(@Param("title") String title, @Param("body") String body,
			@Param("link") String link, @Param("now") LocalDateTime now);

	/** 구버전 경로의 전체 읽음 — 그 앱이 보지 못하는 알림까지 읽음으로 만들면 안 된다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE Notification n
			SET n.readAt = :now
			WHERE n.recipient.id = :recipientId AND n.type = :type AND n.readAt IS NULL
			""")
	int markAllAsReadByType(@Param("recipientId") Long recipientId,
			@Param("type") NotificationType type, @Param("now") LocalDateTime now);
}
