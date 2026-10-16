package sweetgirl.croissant.admin.repository;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.admin.entity.AdminAuditLog;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

	/** 대상 종류·id 는 비어 있으면 거르지 않는다. 최신순(id 내림차순)은 Pageable 의 정렬로 준다. */
	@Query("""
			SELECT a FROM AdminAuditLog a
			WHERE (:targetType IS NULL OR a.targetType = :targetType)
			AND (:targetId IS NULL OR a.targetId = :targetId)
			""")
	Page<AdminAuditLog> search(@Param("targetType") String targetType, @Param("targetId") String targetId,
			Pageable pageable);

	@Modifying
	@Query("DELETE FROM AdminAuditLog a WHERE a.createdAt < :cutoff")
	int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
