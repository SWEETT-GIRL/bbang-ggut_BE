package sweetgirl.croissant.alarm.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.alarm.entity.PushBatchItem;
import sweetgirl.croissant.alarm.enums.NotificationType;

public interface PushBatchItemRepository extends JpaRepository<PushBatchItem, Long> {

	/** 울릴 때가 된 묶음 — 첫 줄이 {@code dueBefore} 이전에 쌓인 (받는 사람, 종류, 보낸 사람). */
	@Query("""
			SELECT i.memberId AS memberId, i.type AS type, i.actorId AS actorId
			FROM PushBatchItem i
			GROUP BY i.memberId, i.type, i.actorId
			HAVING MIN(i.createdAt) <= :dueBefore
			""")
	List<DueGroup> findDueGroups(@Param("dueBefore") LocalDateTime dueBefore);

	List<PushBatchItem> findByMemberIdAndTypeAndActorIdOrderByCreatedAtAsc(Long memberId, NotificationType type,
			Long actorId);

	boolean existsByMemberIdAndNotificationId(Long memberId, Long notificationId);

	interface DueGroup {
		Long getMemberId();

		NotificationType getType();

		Long getActorId();
	}
}
