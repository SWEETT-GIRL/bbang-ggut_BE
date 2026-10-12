package sweetgirl.croissant.store.region;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(timeout = 30)
public class StoreRegionResolutionCommandService {

	private final StoreRegionResolutionRepository repository;
	private final StoreRegionResolutionProperties properties;

	/** 신규 가게, 좌표 변경, 새 경계 버전의 미처리 가게를 같은 경로로 판정한다. */
	public int refreshChangedStores() {
		if (!properties.isEnabled()) {
			return 0;
		}
		if (properties.getVersionKey() == null || properties.getVersionKey().isBlank()) {
			throw new IllegalStateException("지역 소속 갱신에는 경계 버전이 필요합니다.");
		}
		if (properties.getBatchSize() < 1 || properties.getBatchSize() > 2000) {
			throw new IllegalArgumentException("지역 소속 배치 크기는 1~2000이어야 합니다.");
		}
		if (!repository.isReady(properties.getVersionKey())) {
			throw new IllegalStateException("확정된 경계 버전이 없습니다: " + properties.getVersionKey());
		}
		return repository.refreshChanged(properties.getVersionKey(), properties.getBatchSize());
	}
}
