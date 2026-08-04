package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.store.entity.SavedStore;

public interface SavedStoreRepository extends JpaRepository<SavedStore, Long> {

	List<SavedStore> findAllByMemberId(Long memberId);

	long countByMemberId(Long memberId);

	boolean existsByMemberIdAndStoreId(Long memberId, Long storeId);

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
