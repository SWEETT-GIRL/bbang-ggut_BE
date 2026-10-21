package sweetgirl.dataops.serving.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.dataops.serving.entity.ApplyLog;

public interface ApplyLogRepository extends JpaRepository<ApplyLog, Long> {

	Page<ApplyLog> findByStoreIdOrderByAppliedAtDesc(Long storeId, Pageable pageable);

	Page<ApplyLog> findAllByOrderByAppliedAtDesc(Pageable pageable);
}
