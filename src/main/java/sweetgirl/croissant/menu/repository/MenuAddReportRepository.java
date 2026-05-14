package sweetgirl.croissant.menu.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import sweetgirl.croissant.menu.entity.MenuAddReport;
import sweetgirl.croissant.store.enums.StoreReportStatus;

public interface MenuAddReportRepository extends JpaRepository<MenuAddReport, Long> {

	@Query("SELECT r FROM MenuAddReport r JOIN FETCH r.store LEFT JOIN FETCH r.member "
		+ "JOIN FETCH r.menuCategory ORDER BY r.createdAt DESC")
	Page<MenuAddReport> findAllWithDetails(Pageable pageable);

	@Query("SELECT r FROM MenuAddReport r JOIN FETCH r.store LEFT JOIN FETCH r.member "
		+ "JOIN FETCH r.menuCategory WHERE r.status = :status ORDER BY r.createdAt DESC")
	Page<MenuAddReport> findByStatusWithDetails(StoreReportStatus status, Pageable pageable);
}
