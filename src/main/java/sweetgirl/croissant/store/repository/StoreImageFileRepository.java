package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.store.entity.StoreImageFile;

public interface StoreImageFileRepository extends JpaRepository<StoreImageFile, Long> {

	List<StoreImageFile> findByStoreId(Long storeId);
}
