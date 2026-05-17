package sweetgirl.croissant.banner.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.banner.entity.Banner;

public interface BannerRepository extends JpaRepository<Banner, Long> {

	List<Banner> findAllByOrderByDisplayOrderAsc();
}
