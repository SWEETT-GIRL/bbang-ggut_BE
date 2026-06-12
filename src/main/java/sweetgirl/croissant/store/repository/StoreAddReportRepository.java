package sweetgirl.croissant.store.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.store.entity.StoreAddReport;
import sweetgirl.croissant.store.enums.StoreReportStatus;

public interface StoreAddReportRepository extends JpaRepository<StoreAddReport, Long> {

	@Query("SELECT r FROM StoreAddReport r LEFT JOIN FETCH r.member ORDER BY r.createdAt DESC")
	Page<StoreAddReport> findAllWithDetails(Pageable pageable);

	@Query("SELECT r FROM StoreAddReport r LEFT JOIN FETCH r.member WHERE r.status = :status ORDER BY r.createdAt DESC")
	Page<StoreAddReport> findByStatusWithDetails(StoreReportStatus status, Pageable pageable);

	boolean existsByNaverPlaceIdAndStatus(String naverPlaceId, StoreReportStatus status);

	@Query("""
			SELECT COUNT(r) > 0 FROM StoreAddReport r
			WHERE r.status = :status
			AND LOWER(r.name) = LOWER(:name)
			AND LOWER(r.address) = LOWER(:address)
			""")
	boolean existsPendingByNameAndAddress(
			@Param("name") String name,
			@Param("address") String address,
			@Param("status") StoreReportStatus status);

	Optional<StoreAddReport> findById(Long id);
}
