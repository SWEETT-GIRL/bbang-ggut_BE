package sweetgirl.dataops.reconcile.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.dataops.reconcile.entity.StoreFieldProvenance;
import sweetgirl.dataops.reconcile.entity.StoreFieldProvenanceId;

public interface StoreFieldProvenanceRepository
		extends JpaRepository<StoreFieldProvenance, StoreFieldProvenanceId> {

	java.util.List<StoreFieldProvenance> findByStoreId(Long storeId);
}
