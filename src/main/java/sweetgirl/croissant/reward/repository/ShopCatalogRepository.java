package sweetgirl.croissant.reward.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import sweetgirl.croissant.reward.entity.ShopCatalog;

public interface ShopCatalogRepository extends JpaRepository<ShopCatalog, Integer> {

	@Query("SELECT catalog.version FROM ShopCatalog catalog WHERE catalog.id = 1")
	Optional<Integer> findCurrentVersion();
}
