package sweetgirl.croissant.global.logging.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.global.logging.LogEventType;
import sweetgirl.croissant.global.logging.dto.projection.RequestTimingProjection;
import sweetgirl.croissant.global.logging.entity.RequestLog;
import sweetgirl.croissant.store.dto.projection.StoreIdCountProjection;

public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {

	long countByStoreIdAndEventType(Long storeId, LogEventType eventType);

	@Query("SELECT r.storeId AS storeId, COUNT(r) AS count FROM RequestLog r "
		+ "WHERE r.storeId IN :storeIds AND r.eventType = :eventType GROUP BY r.storeId")
	List<StoreIdCountProjection> countByStoreIdInAndEventType(
			@Param("storeIds") List<Long> storeIds,
			@Param("eventType") LogEventType eventType);

	@Query("""
			SELECT r FROM RequestLog r
			WHERE r.createdAt >= :createdAtAfter
				AND (:endpoint IS NULL OR r.endpoint = :endpoint)
			ORDER BY r.responseTimeMs DESC, r.createdAt DESC
			""")
	List<RequestLog> findSlowLogs(
			@Param("createdAtAfter") LocalDateTime createdAtAfter,
			@Param("endpoint") String endpoint,
			Pageable pageable);

	@Query("""
			SELECT r FROM RequestLog r
			WHERE r.createdAt >= :createdAtAfter
				AND (:endpoint IS NULL OR r.endpoint = :endpoint)
			ORDER BY r.createdAt DESC
			""")
	List<RequestLog> findRecentLogs(
			@Param("createdAtAfter") LocalDateTime createdAtAfter,
			@Param("endpoint") String endpoint,
			Pageable pageable);

	/** 기간 안의 (endpoint, 응답시간, 상태) — 엔드포인트별 백분위(/dev/perf/endpoint-stats)용. 응답시간이 없는 행은 뺀다. */
	@Query("""
			SELECT r.endpoint AS endpoint, r.responseTimeMs AS responseTimeMs, r.responseStatus AS responseStatus
			FROM RequestLog r
			WHERE r.createdAt >= :createdAtAfter AND r.responseTimeMs IS NOT NULL
			""")
	List<RequestTimingProjection> findTimingsSince(@Param("createdAtAfter") LocalDateTime createdAtAfter);

	/**
	 * 게스트 → 회원 병합(#663 ④). 04:00 적재가 STORE_VIEW 를 member_id 로 읽으므로, 옮기지 않으면 병합 직전 게스트 조회가 닫힌
	 * 계정으로 적재되어 회원에게 영영 안 붙고 인기도(#663 ③)가 한 사람을 둘로 센다. member_id 인덱스를 탄다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("UPDATE RequestLog log SET log.memberId = :targetId WHERE log.memberId = :guestId")
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);
}
