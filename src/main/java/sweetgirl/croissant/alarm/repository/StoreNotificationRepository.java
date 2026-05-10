package sweetgirl.croissant.alarm.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

public interface StoreNotificationRepository extends JpaRepository<StoreNotification, Long> {
	List<StoreNotification> findByMemberOrderBySentAtDesc(Member member);

	List<StoreNotification> findByMemberAndSentAtAfterOrderBySentAtDesc(Member member, LocalDateTime from);

	boolean existsByMemberAndStoreAndSentAtAfter(Member member, Store store, LocalDateTime after);
}
