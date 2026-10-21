package sweetgirl.dataops.region.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.dataops.region.entity.RegionResolution;

public interface RegionResolutionRepository extends JpaRepository<RegionResolution, Long> {

	/**
	 * 이 대상(가게 ID 또는 후보 관리번호)·이 좌표·지금 기준표/투영 버전의 근거를 최신순으로.
	 *
	 * 좌표는 문자열 지문이 아니라 수치로 맞춘다(반올림 규칙이 파이썬과 자바에서 동점에서 갈릴 수 있다).
	 * 허용 오차 0.0000005° 는 지문의 소수 6자리 반 칸이라, 다른 지문의 좌표를 같은 근거로 읽지 않는다.
	 */
	@Query("SELECT r FROM RegionResolution r WHERE r.subjectType = :subjectType AND r.subjectId = :subjectId "
		+ "AND r.referenceVersion = :referenceVersion AND r.projectionVersion = :projectionVersion "
		+ "AND abs(r.queryLon - :lon) < 0.0000005 AND abs(r.queryLat - :lat) < 0.0000005 "
		+ "ORDER BY r.createdAt DESC, r.id DESC")
	List<RegionResolution> findEvidence(@Param("subjectType") String subjectType,
			@Param("subjectId") String subjectId, @Param("lon") double lon,
			@Param("lat") double lat, @Param("referenceVersion") String referenceVersion,
			@Param("projectionVersion") String projectionVersion, Pageable pageable);
}
