package sweetgirl.dataops.sync.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.sync.code.SyncStateErrorCode;
import sweetgirl.dataops.sync.dto.response.DueStoreResponse;
import sweetgirl.dataops.sync.dto.response.SyncStateResponse;
import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncStateStatus;
import sweetgirl.dataops.sync.enums.SyncTier;
import sweetgirl.dataops.sync.exception.SyncStateException;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class SyncStateCommandService {

	/** 소프트 리스 — 배부 후 이 시간 동안은 due 재배부에서 제외 (겹쳐도 크롤은 멱등이라 무해) */
	static final Duration DISPATCH_LEASE = Duration.ofHours(2);

	private final StoreSyncStateRepository storeSyncStateRepository;

	/**
	 * 워커 워크리스트 export — 원자적 클레임 (v3 P2-1).
	 *
	 * 고르기와 스탬프를 한 문장으로 한다. 이전 구현은 조회 후 스탬프의 두 단계여서 워커 둘이 동시에
	 * 조회하면 같은 가게를 둘 다 받았다 — 병렬 실행을 켜는 순간 같은 가게를 두 번 긁는 결함이었다.
	 * 배부마다 새 claim_token 과 attempt_seq 를 발급하므로, 응답에 실린 세대로 늦게 도착한 이전 시도를 가려낸다.
	 */
	public List<DueStoreResponse> exportDue(int limit) {
		LocalDateTime now = LocalDateTime.now();
		UUID token = UUID.randomUUID();
		int claimed = storeSyncStateRepository.claimDue(
			SyncStateStatus.ACTIVE, now, now.minus(DISPATCH_LEASE), limit, token);
		if (claimed == 0) {
			return List.of();
		}
		return storeSyncStateRepository.findByClaimTokenOrderByNextDueAtAsc(token).stream()
			.map(DueStoreResponse::from).toList();
	}

	public List<DueStoreResponse> exportSelectedDue(int limit, List<Long> storeIds) {
		if (limit < 1 || limit > 50 || storeIds == null || storeIds.isEmpty() || storeIds.size() > 50
			|| storeIds.stream().anyMatch(id -> id == null || id <= 0)) {
			throw new SyncStateException(SyncStateErrorCode.INVALID_DUE_SELECTION);
		}
		LocalDateTime now = LocalDateTime.now();
		UUID token = UUID.randomUUID();
		if (storeSyncStateRepository.claimSelectedDue(storeIds.stream().distinct().toList(), now,
			now.minus(DISPATCH_LEASE), limit, token) == 0) return List.of();
		return storeSyncStateRepository.findByClaimTokenOrderByNextDueAtAsc(token).stream()
			.map(DueStoreResponse::from).toList();
	}

	/** 워커 fetch 실패 보고 — 백오프, 연속 10회면 UNLINKED */
	public SyncStateResponse reportFailure(Long syncStateId) {
		StoreSyncState syncState = getSyncState(syncStateId);
		syncState.recordFailure();
		return SyncStateResponse.from(syncState);
	}

	public SyncStateResponse park(Long syncStateId) {
		StoreSyncState syncState = getSyncState(syncStateId);
		syncState.park();
		return SyncStateResponse.from(syncState);
	}

	public SyncStateResponse unpark(Long syncStateId) {
		StoreSyncState syncState = getSyncState(syncStateId);
		syncState.unpark();
		return SyncStateResponse.from(syncState);
	}

	public SyncStateResponse changeTier(Long syncStateId, SyncTier tier) {
		StoreSyncState syncState = getSyncState(syncStateId);
		syncState.changeTier(tier);
		return SyncStateResponse.from(syncState);
	}

	private StoreSyncState getSyncState(Long syncStateId) {
		return storeSyncStateRepository.findById(syncStateId)
			.orElseThrow(() -> new SyncStateException(SyncStateErrorCode.SYNC_STATE_NOT_FOUND));
	}
}
