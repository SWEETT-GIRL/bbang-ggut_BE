package sweetgirl.dataops.snapshot.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.dataops.snapshot.entity.PlaceSnapshot;
import sweetgirl.dataops.sync.enums.SyncSource;

public interface PlaceSnapshotRepository extends JpaRepository<PlaceSnapshot, Long> {

	Optional<PlaceSnapshot> findTopByStoreIdAndSourceOrderByFetchedAtDesc(Long storeId, SyncSource source);

	/** 최근 스냅샷 몇 개 (최신순) — 정기 영업시간 기억을 여러 관측으로 겹쳐 세울 때 */
	List<PlaceSnapshot> findTop6ByStoreIdAndSourceOrderByFetchedAtDesc(Long storeId, SyncSource source);

	Page<PlaceSnapshot> findByStoreIdOrderByFetchedAtDesc(Long storeId, Pageable pageable);

	@org.springframework.data.jpa.repository.Query("SELECT DISTINCT p.storeId FROM PlaceSnapshot p WHERE p.source = :source")
	java.util.List<Long> findDistinctStoreIdsBySource(SyncSource source);
}
