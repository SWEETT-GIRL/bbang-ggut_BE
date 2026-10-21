package sweetgirl.croissant.store.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class StoreRegionResolutionCommandServiceTest {

	@Mock
	private StoreRegionResolutionRepository repository;
	private StoreRegionResolutionProperties properties;
	private StoreRegionResolutionCommandService service;

	@BeforeEach
	void setUp() {
		properties = new StoreRegionResolutionProperties();
		service = new StoreRegionResolutionCommandService(repository, properties);
	}

	@Test
	void disabledByDefaultDoesNotReadOrWriteDatabase() {
		assertThat(service.refreshChangedStores()).isZero();
		verifyNoInteractions(repository);
	}

	@Test
	void requiresExplicitReadyVersion() {
		properties.setEnabled(true);
		assertThatThrownBy(service::refreshChangedStores).isInstanceOf(IllegalStateException.class);
		verifyNoInteractions(repository);
		properties.setVersionKey("unknown");
		assertThatThrownBy(service::refreshChangedStores).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("확정된 경계 버전");
	}

	@Test
	void boundsBatchSizeBeforeAccessingDatabase() {
		properties.setEnabled(true);
		properties.setVersionKey("v1");
		for (int invalid : new int[] {0, -1, 2001}) {
			properties.setBatchSize(invalid);
			assertThatThrownBy(service::refreshChangedStores).isInstanceOf(IllegalArgumentException.class);
		}
		verifyNoInteractions(repository);
	}

	@Test
	void refreshesOneBatchOfConfiguredVersion() {
		properties.setEnabled(true);
		properties.setVersionKey("v1");
		when(repository.isReady("v1")).thenReturn(true);
		when(repository.refreshChanged("v1", 500)).thenReturn(123);
		assertThat(service.refreshChangedStores()).isEqualTo(123);
	}

	@Test
	void databaseFailurePropagatesForTransactionRollback() {
		properties.setEnabled(true);
		properties.setVersionKey("v1");
		when(repository.isReady("v1")).thenReturn(true);
		when(repository.refreshChanged("v1", 500)).thenThrow(new DataAccessResourceFailureException("unavailable"));
		assertThatThrownBy(service::refreshChangedStores).isInstanceOf(DataAccessResourceFailureException.class);
	}
}
