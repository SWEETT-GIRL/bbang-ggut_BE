package sweetgirl.croissant.store.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.store.entity.Store;

public interface StoreRepository extends JpaRepository<Store, Long> {

}
