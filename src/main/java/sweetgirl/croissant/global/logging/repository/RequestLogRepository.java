package sweetgirl.croissant.global.logging.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.global.logging.entity.RequestLog;

public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {

	List<RequestLog> findByStoreIdAndEventTypeAndCreatedAtAfter(Long storeId, String eventType,
			LocalDateTime after);
}
