package sweetgirl.croissant.alarm.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

public interface StoreNotificationRepository extends JpaRepository<StoreNotification, Long> {
	List<StoreNotification> findByMemberOrderBySentAtDesc(Member member);

	List<StoreNotification> findByMemberAndSentAtAfterOrderBySentAtDesc(Member member, LocalDateTime from);

	// 오늘 이미 알림을 발송한 storeId 목록을 한 번에 조회 — 루프 내 N+1 방지
	@Query("SELECT sn.store.id FROM StoreNotification sn WHERE sn.member = :member AND sn.sentAt > :after")
	Set<Long> findNotifiedStoreIdsSince(@Param("member") Member member, @Param("after") LocalDateTime after);

	// 중복 알림 방지용 -- 같은 멤버 + 같은 가게에 대해 sentAt이 특정 시각 이후인 알림 이력 존재하는지 확인 (오늘 보냈나?)
	boolean existsByMemberAndStoreAndSentAtAfter(Member member, Store store, LocalDateTime from);
}
