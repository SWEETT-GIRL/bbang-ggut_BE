package sweetgirl.croissant.global.logging.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.global.logging.LogEventType;
import sweetgirl.croissant.global.logging.entity.RequestLog;
import sweetgirl.croissant.store.dto.projection.StoreIdCountProjection;

public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {

	List<RequestLog> findByStoreIdAndEventTypeAndCreatedAtAfter(Long storeId, LogEventType eventType,
			LocalDateTime after);

	long countByStoreIdAndEventType(Long storeId, LogEventType eventType);

	@Query("SELECT r.storeId AS storeId, COUNT(r) AS count FROM RequestLog r "
		+ "WHERE r.storeId IN :storeIds AND r.eventType = :eventType GROUP BY r.storeId")
	List<StoreIdCountProjection> countByStoreIdInAndEventType(
			@Param("storeIds") List<Long> storeIds,
			@Param("eventType") LogEventType eventType);
}
