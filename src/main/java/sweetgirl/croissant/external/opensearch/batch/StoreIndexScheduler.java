package sweetgirl.croissant.external.opensearch.batch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.external.opensearch.service.StoreIndexService;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.repository.StoreRepository;

@Slf4j
@Component
@ConditionalOnProperty(name = "opensearch.endpoint")
@RequiredArgsConstructor
public class StoreIndexScheduler {

	private static final int BATCH_SIZE = 100;
	private static final long BATCH_DELAY_MS = 1000;

	private final StoreRepository storeRepository;
	private final StoreIndexService storeIndexService;

	@Scheduled(cron = "0 0 3 * * MON", zone = "Asia/Seoul")
	public void weeklyScoreUpdate() {
		log.info("[OpenSearch] 주간 popularityScore 배치 시작");
		int page = 0;
		long updated = 0;

		while (true) {
			Page<Store> stores = storeRepository.findByStatus(StoreStatus.ACTIVE, PageRequest.of(page, BATCH_SIZE));
			if (stores.isEmpty()) {
				break;
			}
			storeIndexService.bulkUpdateScores(stores.getContent());
			updated += stores.getNumberOfElements();
			if (!stores.hasNext()) {
				break;
			}
			page++;
			try {
				Thread.sleep(BATCH_DELAY_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		log.info("[OpenSearch] 주간 popularityScore 배치 완료 updated={}", updated);
	}
}
