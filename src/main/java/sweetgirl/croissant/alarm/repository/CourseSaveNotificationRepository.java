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
	 * <p>Slice라 총 개수를 세지 않는다(무한스크롤엔 hasNext면 충분하다).
	 */
	@Query("""
			SELECT n FROM CourseSaveNotification n
			JOIN FETCH n.actor
			JOIN FETCH n.course
			WHERE n.owner.id = :ownerId
			ORDER BY n.notifiedAt DESC
			""")
	Slice<CourseSaveNotification> findByOwner(@Param("ownerId") Long ownerId, Pageable pageable);

	Optional<CourseSaveNotification> findByActorIdAndCourseId(Long actorId, Long courseId);

	long countByOwnerIdAndReadAtIsNull(Long ownerId);

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
