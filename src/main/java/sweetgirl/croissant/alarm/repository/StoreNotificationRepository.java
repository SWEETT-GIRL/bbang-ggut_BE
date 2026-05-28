package sweetgirl.croissant.alarm.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

public interface StoreNotificationRepository extends JpaRepository<StoreNotification, Long> {
	List<StoreNotification> findByMemberOrderBySentAtDesc(Member member);

	List<StoreNotification> findByMemberAndSentAtAfterOrderBySentAtDesc(Member member, LocalDateTime from);

	// 중복 알림 방지 — 같은 멤버 + 같은 가게에 오늘 이미 발송했는지 확인
	boolean existsByMemberAndStoreAndSentAtAfter(Member member, Store store, LocalDateTime from);

	// (memberId, storeId) 단위 동시 발송 직렬화용 Postgres 트랜잭션 advisory lock
	// 잠금 획득 실패 시 false 반환(non-blocking). 트랜잭션 종료 시 자동 해제.
	@Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
	boolean tryAdvisoryXactLock(@Param("key") long key);
}
