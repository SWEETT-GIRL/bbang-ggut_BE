package sweetgirl.croissant.external.opensearch.batch;

import org.opensearch.client.opensearch.core.BulkResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.external.opensearch.dto.ReindexResultDto;
import sweetgirl.croissant.external.opensearch.service.StoreIndexService;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.repository.StoreRepository;

@Slf4j
@Service
@ConditionalOnProperty(name = "opensearch.endpoint")
@RequiredArgsConstructor
public class StoreDocumentIndexer {
	private static final int BATCH_SIZE = 50;
	private static final long BATCH_DELAY_MS = 1500;

	private final StoreRepository storeRepository;
	private final StoreIndexService storeIndexService;

	public ReindexResultDto reindexAllStores() {
		long success = 0;
		long failed = 0;
		int page = 0;

		while (true) {
			Page<Store> stores = storeRepository.findByStatus(StoreStatus.ACTIVE, PageRequest.of(page, BATCH_SIZE));
			if (stores.isEmpty()) {
				break;
			}
			try {
				BulkResponse response = storeIndexService.bulkIndexStores(stores.getContent());
				if (response.errors()) {
					long errorCount = response.items().stream()
						.filter(i -> i.error() != null)
						.count();
					success += stores.getNumberOfElements() - errorCount;
					failed += errorCount;
					log.warn("벌크 색인 일부 실패 page={} errors={}", page, errorCount);
				} else {
					success += stores.getNumberOfElements();
				}
			} catch (Exception e) {
				failed += stores.getNumberOfElements();
				log.error("벌크 색인 실패 page={}", page, e);
			}
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

		log.info("reindexAllStores 완료 success={} failed={}", success, failed);
		return new ReindexResultDto(success, failed);
	}
}
