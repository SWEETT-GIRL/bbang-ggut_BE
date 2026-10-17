package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.store.dto.projection.StoreNameAliasProjection;
import sweetgirl.croissant.store.entity.StoreNameAlias;

public interface StoreNameAliasRepository extends JpaRepository<StoreNameAlias, Long> {

	@Query("""
			SELECT a.store.id AS storeId, a.alias AS alias
			FROM StoreNameAlias a
			WHERE a.store.id IN :storeIds
			""")
	List<StoreNameAliasProjection> findAliasesForIndexingByStoreIds(@Param("storeIds") List<Long> storeIds);

	@Query("SELECT a.alias FROM StoreNameAlias a WHERE a.store.id = :storeId")
	List<String> findAliasesForIndexingByStoreId(@Param("storeId") Long storeId);
}
