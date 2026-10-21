package sweetgirl.dataops.sync.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import sweetgirl.dataops.sync.dto.response.DueStoreResponse;
import sweetgirl.dataops.sync.dto.response.SyncStateResponse;
import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.enums.SyncStateStatus;
import sweetgirl.dataops.sync.enums.SyncTier;
import sweetgirl.dataops.sync.exception.SyncStateException;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;

@ExtendWith(MockitoExtension.class)
class SyncStateCommandServiceTest {

	@Mock
	private StoreSyncStateRepository storeSyncStateRepository;

	@InjectMocks
	private SyncStateCommandService syncStateCommandService;

	private StoreSyncState createState() {
		return StoreSyncState.builder()
			.storeId(1L)
			.source(SyncSource.NAVER)
			.externalId("naver-123")
			.tier(SyncTier.T1)
			.build();
	}

	@Test
	void exportDue_는_한_문장으로_클레임하고_그_토큰으로만_읽는다() {
		// 조회 후 스탬프의 두 단계면 워커 둘이 같은 가게를 받는다 — 클레임 한 문장이 그 사이를 없앤다 (v3 P2-1)
		StoreSyncState syncState = createState();
		given(storeSyncStateRepository.claimDue(
			eq(SyncStateStatus.ACTIVE), any(LocalDateTime.class), any(LocalDateTime.class), eq(50), any(UUID.class)))
			.willReturn(1);
		given(storeSyncStateRepository.findByClaimTokenOrderByNextDueAtAsc(any(UUID.class)))
			.willReturn(List.of(syncState));

		List<DueStoreResponse> dueStores = syncStateCommandService.exportDue(50);

		assertThat(dueStores).hasSize(1);
		assertThat(dueStores.get(0).storeId()).isEqualTo(1L);
		assertThat(dueStores.get(0).externalId()).isEqualTo("naver-123");
		// 클레임이 0건이면 굳이 읽지 않는다
		then(storeSyncStateRepository).should(never()).findDue(any(), any(), any(), any());
	}

	@Test
	void exportDue_는_클레임이_없으면_조회하지_않는다() {
		given(storeSyncStateRepository.claimDue(
			eq(SyncStateStatus.ACTIVE), any(LocalDateTime.class), any(LocalDateTime.class), eq(50), any(UUID.class)))
			.willReturn(0);

		assertThat(syncStateCommandService.exportDue(50)).isEmpty();
		then(storeSyncStateRepository).should(never()).findByClaimTokenOrderByNextDueAtAsc(any());
	}

	@Test
	void reportFailure_백오프를_적용한다() {
		StoreSyncState syncState = createState();
		given(storeSyncStateRepository.findById(1L)).willReturn(Optional.of(syncState));

		SyncStateResponse response = syncStateCommandService.reportFailure(1L);

		assertThat(response.consecutiveFailures()).isEqualTo(1);
	}

	@Test
	void reportFailure_없는_id_면_예외를_던진다() {
		given(storeSyncStateRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> syncStateCommandService.reportFailure(99L))
			.isInstanceOf(SyncStateException.class);
	}

	@Test
	void park_와_changeTier_는_엔티티에_위임한다() {
		StoreSyncState syncState = createState();
		given(storeSyncStateRepository.findById(1L)).willReturn(Optional.of(syncState));

		syncStateCommandService.park(1L);
		assertThat(syncState.getState()).isEqualTo(SyncStateStatus.PARKED);

		SyncStateResponse response = syncStateCommandService.changeTier(1L, SyncTier.T0);
		assertThat(response.tier()).isEqualTo(SyncTier.T0);
	}
}
