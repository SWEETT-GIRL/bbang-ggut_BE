package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.store.entity.StoreReportImageFile;

public interface StoreReportImageFileRepository extends JpaRepository<StoreReportImageFile, Long> {

	List<StoreReportImageFile> findByStoreReportIdIn(List<Long> reportIds);
}
