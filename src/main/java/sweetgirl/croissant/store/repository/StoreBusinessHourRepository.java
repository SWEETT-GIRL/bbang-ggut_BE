package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.store.entity.StoreBusinessHour;

public interface StoreBusinessHourRepository extends JpaRepository<StoreBusinessHour, Long> {
	List<StoreBusinessHour> findByStoreIdOrderByDayOfWeekAsc(Long storeId);
}
