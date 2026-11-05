package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.store.entity.SavedStore;
import sweetgirl.croissant.store.entity.Store;

public interface SavedStoreRepository extends JpaRepository<SavedStore, Long> {

	// 담은 순서의 유일한 근거가 PK다 — 최근 담은 것부터 내려줘야 홈·목록이 최신순이 된다.
	List<SavedStore> findAllByMemberIdOrderByIdDesc(Long memberId);

	long countByMemberId(Long memberId);

	boolean existsByMemberIdAndStoreId(Long memberId, Long storeId);

	/** 담아 뒀는데 아직 기록이 없는 활성 가게 — 최근 담은 것부터. 방금 기록한 가게({@code excludeStoreId}, 없으면 -1)는 뺀다. */
	@Query("""
			SELECT s.store FROM SavedStore s
			WHERE s.member.id = :memberId AND s.store.id <> :excludeStoreId
				AND s.store.status = sweetgirl.croissant.store.enums.StoreStatus.ACTIVE
				AND NOT EXISTS (SELECT 1 FROM BreadRecord r
					WHERE r.member.id = :memberId AND r.store.id = s.store.id AND r.deletedAt IS NULL)
			ORDER BY s.id DESC
			""")
	List<Store> findUnrecordedActiveStores(@Param("memberId") Long memberId,
			@Param("excludeStoreId") Long excludeStoreId, Pageable pageable);

	void deleteByMemberIdAndStoreId(Long memberId, Long storeId);

	// ── 게스트 → 회원 데이터 이관 ─────────────────────────────
	@Modifying(clearAutomatically = true)
	@Query("DELETE FROM SavedStore s WHERE s.member.id = :guestId AND EXISTS "
		+ "(SELECT 1 FROM SavedStore t WHERE t.member.id = :targetId AND t.store.id = s.store.id)")
	int deleteGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Modifying(clearAutomatically = true)
	@Query("UPDATE SavedStore s SET s.member.id = :targetId WHERE s.member.id = :guestId")
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

}
