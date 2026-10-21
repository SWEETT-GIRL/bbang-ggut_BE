package sweetgirl.dataops.sync.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import sweetgirl.dataops.reconcile.entity.StoreFieldProvenance;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.reconcile.repository.StoreFieldProvenanceRepository;
import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.dto.MatchedStore;
import sweetgirl.dataops.serving.dto.UserContributedMenu;
import sweetgirl.dataops.serving.exception.ServingException;
import sweetgirl.dataops.sync.dto.response.SeedResultResponse;
import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;

@ExtendWith(MockitoExtension.class)
class SyncSeedServiceTest {

	@Mock
	private ObjectProvider<ServingReader> servingReaderProvider;
	@Mock
	private ServingReader servingReader;
	@Mock
	private StoreSyncStateRepository storeSyncStateRepository;
	@Mock
	private StoreFieldProvenanceRepository storeFieldProvenanceRepository;

	@InjectMocks
	private SyncSeedService syncSeedService;

	@Test
	void seed_운영_DB_미설정이면_예외를_던진다() {
		given(servingReaderProvider.getIfAvailable()).willReturn(null);

		assertThatThrownBy(() -> syncSeedService.seed()).isInstanceOf(ServingException.class);
	}

	@Test
	void seed_기존_sync_state_가_없는_가게만_생성한다_멱등() {
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(servingReader.findActiveMatchedStores()).willReturn(List.of(
			new MatchedStore(1L, "naver-1"),
			new MatchedStore(2L, "naver-2"),
			new MatchedStore(3L, "naver-3")));
		given(storeSyncStateRepository.findStoreIdsBySource(SyncSource.NAVER))
			.willReturn(List.of(2L));
		given(servingReader.findUserContributedMenus()).willReturn(List.of());

		SeedResultResponse result = syncSeedService.seed();

		assertThat(result.matchedStores()).isEqualTo(3);
		assertThat(result.created()).isEqualTo(2);
		assertThat(result.skipped()).isEqualTo(1);

		ArgumentCaptor<List<StoreSyncState>> captor = ArgumentCaptor.captor();
		then(storeSyncStateRepository).should().saveAll(captor.capture());
		assertThat(captor.getValue())
			.extracting(StoreSyncState::getStoreId)
			.containsExactly(1L, 3L);
		assertThat(captor.getValue().get(0).getExternalId()).isEqualTo("naver-1");
	}

	@Test
	void seed_사용자_기여_메뉴에_provenance_USER_를_소급한다() {
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(servingReader.findActiveMatchedStores()).willReturn(List.of());
		given(storeSyncStateRepository.findStoreIdsBySource(any())).willReturn(List.of());
		given(servingReader.findUserContributedMenus()).willReturn(List.of(
			new UserContributedMenu(1L, 100L),
			new UserContributedMenu(2L, 200L)));

		SeedResultResponse result = syncSeedService.seed();

		assertThat(result.provenanceBackfilled()).isEqualTo(2);

		ArgumentCaptor<List<StoreFieldProvenance>> captor = ArgumentCaptor.captor();
		then(storeFieldProvenanceRepository).should().saveAll(captor.capture());
		assertThat(captor.getValue())
			.extracting(StoreFieldProvenance::getField)
			.containsExactly("menu.100", "menu.200");
		assertThat(captor.getValue())
			.allSatisfy(provenance -> assertThat(provenance.getSource()).isEqualTo(ProvenanceSource.USER));
	}
}
