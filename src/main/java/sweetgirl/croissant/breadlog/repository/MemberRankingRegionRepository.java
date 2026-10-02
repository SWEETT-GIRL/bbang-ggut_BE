package sweetgirl.croissant.breadlog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import sweetgirl.croissant.breadlog.dto.projection.VerifiedRegion;
import sweetgirl.croissant.breadlog.entity.MemberRankingRegion;

public interface MemberRankingRegionRepository extends JpaRepository<MemberRankingRegion, Long> {

	/** 누군가 인증한 구 전부(시·도 + 시·군·구) — 스냅샷이 구마다 내 지역 판을 남길 때. */
	@Query("""
			SELECT DISTINCT new sweetgirl.croissant.breadlog.dto.projection.VerifiedRegion(r.sido, r.sigungu)
			FROM MemberRankingRegion r
			WHERE r.sigungu IS NOT NULL
			""")
	List<VerifiedRegion> findVerifiedRegions();
}
