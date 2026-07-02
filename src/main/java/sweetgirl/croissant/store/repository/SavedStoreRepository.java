package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.store.entity.SavedStore;

public interface SavedStoreRepository extends JpaRepository<SavedStore, Long> {

	List<SavedStore> findAllByMemberId(Long memberId);

	boolean existsByMemberIdAndStoreId(Long memberId, Long storeId);

	void deleteByMemberIdAndStoreId(Long memberId, Long storeId);
}
