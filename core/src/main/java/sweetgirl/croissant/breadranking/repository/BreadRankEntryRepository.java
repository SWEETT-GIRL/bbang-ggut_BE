package sweetgirl.croissant.breadranking.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.breadranking.entity.BreadRankEntry;

public interface BreadRankEntryRepository extends JpaRepository<BreadRankEntry, Long> {

	/** 한 카테고리의 내 줄 전부(등수 있는 것·없는 것) — 가게까지 한 번에. 정렬은 서비스가 한다(null 등수 순서가 DB 마다 달라서). */
	@Query("""
			SELECT entry
			FROM BreadRankEntry entry
			JOIN FETCH entry.store
			WHERE entry.memberId = :memberId AND entry.category.id = :categoryId
			""")
	List<BreadRankEntry> findAllWithStoreByMemberIdAndCategoryId(
			@Param("memberId") Long memberId,
			@Param("categoryId") Long categoryId);

	Optional<BreadRankEntry> findByMemberIdAndCategoryIdAndStoreId(Long memberId, Long categoryId, Long storeId);

	/** 내 모든 카테고리의 1등 — 마이 "내 빵 순위" 줄과 탭 목록이 쓴다. 공동 1등이면 여러 줄. */
	@Query("""
			SELECT entry
			FROM BreadRankEntry entry
			JOIN FETCH entry.store
			WHERE entry.memberId = :memberId AND entry.rank = 1
			ORDER BY entry.category.id ASC, entry.rankedAt ASC, entry.id ASC
			""")
	List<BreadRankEntry> findTopEntriesWithStoreByMemberId(@Param("memberId") Long memberId);

	void deleteAllByMemberIdAndCategoryId(Long memberId, Long categoryId);

	/**
	 * 등수 없이 담는다 — 이미 있으면 그대로(0). 저장 뒤 [순위 매기기] 더블탭·재시도가 유니크 위반 500 이 되지 않게 DB 가 한 번만 받는다.
	 *
	 * @return 넣었으면 1, 이미 있어 안 넣었으면 0
	 */
	@Modifying
	@Query(value = """
			INSERT INTO bread_rank_entry (member_id, category_id, store_id, created_at)
			VALUES (:memberId, :categoryId, :storeId, NOW())
			ON CONFLICT (member_id, category_id, store_id) DO NOTHING
			""", nativeQuery = true)
	int insertUnrankedIfAbsent(
			@Param("memberId") Long memberId,
			@Param("categoryId") Long categoryId,
			@Param("storeId") Long storeId);
}
