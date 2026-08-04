package sweetgirl.croissant.breadlog.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import sweetgirl.croissant.breadlog.entity.BreadRecord;

public interface BreadRecordRepository extends JpaRepository<BreadRecord, Long>, JpaSpecificationExecutor<BreadRecord> {

	Optional<BreadRecord> findByIdAndDeletedAtIsNull(Long id);
}
