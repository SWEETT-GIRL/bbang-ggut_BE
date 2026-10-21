package sweetgirl.dataops.sync.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.dataops.reconcile.entity.StoreFieldProvenance;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.reconcile.repository.StoreFieldProvenanceRepository;
import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.code.ServingErrorCode;
import sweetgirl.dataops.serving.dto.MatchedStore;
import sweetgirl.dataops.serving.dto.UserContributedMenu;
import sweetgirl.dataops.serving.exception.ServingException;
import sweetgirl.dataops.sync.dto.response.SeedResultResponse;
import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.enums.SyncTier;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;

/**
 * 최초 시딩 (멱등) — 운영의 네이버 매칭 가게를 sync_state 로 벌크 생성하고 naver_place_id 를 복사한다.
 * 함께, 사용자 기여 메뉴에 provenance USER 를 소급 기록한다 — 재수집이 사용자 기여를 덮어쓰지 않게 하는
 * 선행조건 (리서치 2.5).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncSeedService {

	private final ObjectProvider<ServingReader> servingReaderProvider;
	private final StoreSyncStateRepository storeSyncStateRepository;
	private final StoreFieldProvenanceRepository storeFieldProvenanceRepository;

	@Transactional
	public SeedResultResponse seed() {
		ServingReader servingReader = servingReaderProvider.getIfAvailable();
		if (servingReader == null) {
			throw new ServingException(ServingErrorCode.PROD_DB_NOT_CONFIGURED);
		}

		List<MatchedStore> matchedStores = servingReader.findActiveMatchedStores();
		Set<Long> existingStoreIds = new HashSet<>(
			storeSyncStateRepository.findStoreIdsBySource(SyncSource.NAVER));

		List<StoreSyncState> newStates = matchedStores.stream()
			.filter(store -> !existingStoreIds.contains(store.storeId()))
			.map(store -> StoreSyncState.builder()
				.storeId(store.storeId())
				.source(SyncSource.NAVER)
				.externalId(store.naverPlaceId())
				.tier(SyncTier.T1)
				.build())
			.toList();
		storeSyncStateRepository.saveAll(newStates);

		long provenanceBackfilled = backfillUserContributedMenus(servingReader);

		log.info("시딩 완료 — 매칭 {}건, 신규 {}건, 스킵 {}건, provenance USER 소급 {}건",
			matchedStores.size(), newStates.size(), matchedStores.size() - newStates.size(),
			provenanceBackfilled);
		return new SeedResultResponse(
			matchedStores.size(), newStates.size(),
			matchedStores.size() - newStates.size(), provenanceBackfilled);
	}

	private long backfillUserContributedMenus(ServingReader servingReader) {
		List<UserContributedMenu> userMenus = servingReader.findUserContributedMenus();
		List<StoreFieldProvenance> provenances = userMenus.stream()
			.map(menu -> StoreFieldProvenance.builder()
				.storeId(menu.storeId())
				.field(StoreFieldProvenance.menuField(menu.menuId()))
				.source(ProvenanceSource.USER)
				.build())
			.toList();
		storeFieldProvenanceRepository.saveAll(provenances);
		return provenances.size();
	}
}
