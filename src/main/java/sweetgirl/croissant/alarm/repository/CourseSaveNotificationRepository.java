package sweetgirl.croissant.alarm.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.alarm.entity.CourseSaveNotification;

public interface CourseSaveNotificationRepository extends JpaRepository<CourseSaveNotification, Long> {

	/**
	 * 목록 조회. actor·course를 같이 끌어온다 — 응답에 닉네임과 코스 제목이 들어가는데
	 * LAZY로 두면 20줄에 40번 더 나간다.
	 *
	 * <p>지운 코스의 알림은 뺀다. 알림 줄을 누르면 그 코스로 보내는데 이미 지웠으면 상세가
	 * 404로 떨어진다 — 눌러도 아무 데도 못 가는 줄을 남겨둘 이유가 없다. 행 자체를 지우지는
	 * 않는다. 찜은 실제로 일어났던 일이다.
	 *
	 * <p>Slice라 총 개수를 세지 않는다(무한스크롤엔 hasNext면 충분하다).
	 */
	@Query("""
			SELECT n FROM CourseSaveNotification n
			JOIN FETCH n.actor
			JOIN FETCH n.course c
			WHERE n.owner.id = :ownerId AND c.deletedAt IS NULL
			ORDER BY n.notifiedAt DESC
			""")
	Slice<CourseSaveNotification> findByOwner(@Param("ownerId") Long ownerId, Pageable pageable);

	Optional<CourseSaveNotification> findByActorIdAndCourseId(Long actorId, Long courseId);

	/**
	 * 벨 배지용 미읽음 개수. 목록과 조건이 같아야 한다 — 지운 코스의 알림을 여기서만 세면
	 * "점은 켜져 있는데 들어가 보면 아무것도 없는" 상태가 된다.
	 */
	@Query("""
			SELECT COUNT(n) FROM CourseSaveNotification n
			WHERE n.owner.id = :ownerId AND n.readAt IS NULL AND n.course.deletedAt IS NULL
			""")
	long countUnreadByOwner(@Param("ownerId") Long ownerId);

	List<CourseSaveNotification> findByOwnerIdAndIdIn(Long ownerId, List<Long> ids);

	/**
	 * 전체 읽음. 한 줄씩 불러 고치면 알림이 쌓인 사람에게 그만큼 UPDATE가 나간다.
	 * 벌크 UPDATE는 영속성 컨텍스트를 안 거치므로 호출부에서 이후 조회를 하지 않는다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE CourseSaveNotification n
			SET n.readAt = :now
			WHERE n.owner.id = :ownerId AND n.readAt IS NULL
			""")
	int markAllAsRead(@Param("ownerId") Long ownerId, @Param("now") java.time.LocalDateTime now);
}
