package sweetgirl.croissant.external.opensearch.event;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.external.opensearch.service.StoreIndexService;

@ExtendWith(MockitoExtension.class)
class StoreIndexEventListenerTest {

	@Mock
	StoreIndexService storeIndexService;

	@InjectMocks
	StoreIndexEventListener storeIndexEventListener;

	@Test
	@DisplayName("onAddressChanged: StoreAddressChangedEvent 수신 시 storeIndexService.updateStoreAddress를 호출한다")
	void onAddressChanged_callsUpdateStoreAddress() {
		StoreAddressChangedEvent event = new StoreAddressChangedEvent(42L);

		storeIndexEventListener.onAddressChanged(event);

		verify(storeIndexService).updateStoreAddress(42L);
	}

	@Test
	@DisplayName("onMenusChanged: StoreMenusChangedEvent 수신 시 storeIndexService.updateStoreMenus를 호출한다")
	void onMenusChanged_callsUpdateStoreMenus() {
		StoreMenusChangedEvent event = new StoreMenusChangedEvent(99L);

		storeIndexEventListener.onMenusChanged(event);

		verify(storeIndexService).updateStoreMenus(99L);
	}

	@Test
	@DisplayName("onStoreNameChanged: StoreNameChangedEvent 수신 시 storeIndexService.updateStoreName을 호출한다")
	void onStoreNameChanged_callsUpdateStoreName() {
		StoreNameChangedEvent event = new StoreNameChangedEvent(55L);

		storeIndexEventListener.onStoreNameChanged(event);

		verify(storeIndexService).updateStoreName(55L);
	}

	@Test
	@DisplayName("onStoreDeleted: StoreDeletedEvent 수신 시 storeIndexService.deleteStoreDocument를 호출한다")
	void onStoreDeleted_callsDeleteStoreDocument() {
		StoreDeletedEvent event = new StoreDeletedEvent(7L);

		storeIndexEventListener.onStoreDeleted(event);

		verify(storeIndexService).deleteStoreDocument(7L);
	}
}
