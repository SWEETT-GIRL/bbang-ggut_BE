package sweetgirl.croissant.external.opensearch.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.opensearch.service.StoreIndexService;

@Component
@ConditionalOnProperty(name = "opensearch.endpoint")
@RequiredArgsConstructor
public class StoreIndexEventListener {

	private final StoreIndexService storeIndexService;

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onStoreCreated(StoreCreatedEvent event) {
		storeIndexService.indexStore(event.storeId());
	}

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onAddressChanged(StoreAddressChangedEvent event) {
		storeIndexService.updateStoreAddress(event.storeId());
	}

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onMenusChanged(StoreMenusChangedEvent event) {
		storeIndexService.updateStoreMenus(event.storeId());
	}

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onStoreNameChanged(StoreNameChangedEvent event) {
		storeIndexService.updateStoreName(event.storeId());
	}

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onStoreDeleted(StoreDeletedEvent event) {
		storeIndexService.deleteStoreDocument(event.storeId());
	}
}
