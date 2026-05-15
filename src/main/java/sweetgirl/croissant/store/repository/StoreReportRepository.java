package sweetgirl.croissant.store.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import sweetgirl.croissant.store.entity.StoreReport;
import sweetgirl.croissant.store.enums.StoreReportStatus;

public interface StoreReportRepository extends JpaRepository<StoreReport, Long> {

	@Query("SELECT r FROM StoreReport r JOIN FETCH r.store LEFT JOIN FETCH r.member ORDER BY r.createdAt DESC")
	Page<StoreReport> findAllWithDetails(Pageable pageable);

	@Query("SELECT r FROM StoreReport r JOIN FETCH r.store LEFT JOIN FETCH r.member "
		+ "WHERE r.status = :status ORDER BY r.createdAt DESC")
	Page<StoreReport> findByStatusWithDetails(StoreReportStatus status, Pageable pageable);
}
