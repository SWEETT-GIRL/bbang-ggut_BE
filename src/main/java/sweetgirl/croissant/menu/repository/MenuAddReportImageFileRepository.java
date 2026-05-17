package sweetgirl.croissant.menu.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.menu.entity.MenuAddReportImageFile;

public interface MenuAddReportImageFileRepository extends JpaRepository<MenuAddReportImageFile, Long> {

	List<MenuAddReportImageFile> findByMenuAddReportIdIn(List<Long> reportIds);
}
