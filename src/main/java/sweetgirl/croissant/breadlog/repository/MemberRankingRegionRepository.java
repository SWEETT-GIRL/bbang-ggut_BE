package sweetgirl.croissant.breadlog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

	/** 그 구로 인증한 사람 수 — 탈퇴·게스트는 뺀다. 내 지역 판은 이 수가 모자라면 열지 않는다. */
	@Query("""
			SELECT COUNT(r)
			FROM MemberRankingRegion r, Member m
			WHERE m.id = r.memberId
				AND m.deletedAt IS NULL
				AND m.memberType = 'USER'
				AND r.sido = :sido
				AND r.sigungu = :sigungu
			""")
	long countMembers(@Param("sido") String sido, @Param("sigungu") String sigungu);
}
