package sweetgirl.croissant.alarm.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;

/**
 * 알릴 내용 그 자체. 목록 조회는 여기를 거치지 않는다 —
 * 알림함이 보는 것은 {@link NotificationRecipientRepository} 쪽이다.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

	/**
	 * 같은 사람이 같은 대상에 같은 행동을 반복했을 때 쓰던 줄을 찾는다(저장 껐다 켜기).
	 * 활동 알림은 받는 사람이 한 명이라 내용 쪽에서 찾아도 남의 알림과 섞이지 않는다.
	 */
	List<Notification> findAllByTargetTypeAndTargetId(NotificationTargetType targetType, Long targetId);

	Optional<Notification> findByActorIdAndTypeAndTargetTypeAndTargetId(Long actorId,
			NotificationType type, NotificationTargetType targetType, Long targetId);

	/** 한 종류의 알림을 최근 것부터 — 어드민의 보낸 편지 목록. */
	Slice<Notification> findByTypeOrderByIdDesc(NotificationType type, Pageable pageable);

	/** 그 뒤로 이 종류의 알림을 만든 적이 있는가 — 같은 정기 발송을 두 번 내보내지 않으려고 본다. */
	boolean existsByTypeAndCreatedAtGreaterThanEqual(NotificationType type, LocalDateTime since);
}
