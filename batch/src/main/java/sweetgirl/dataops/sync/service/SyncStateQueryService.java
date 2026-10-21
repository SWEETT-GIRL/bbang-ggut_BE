package sweetgirl.dataops.sync.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.global.response.PageResponse;
import sweetgirl.dataops.sync.dto.response.SyncStateResponse;
import sweetgirl.dataops.sync.enums.SyncStateStatus;
import sweetgirl.dataops.sync.enums.SyncTier;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SyncStateQueryService {

	private final StoreSyncStateRepository storeSyncStateRepository;

	public PageResponse<SyncStateResponse> getSyncStates(
			SyncTier tier, SyncStateStatus state, int page, int size) {
		return PageResponse.from(
			storeSyncStateRepository.findByFilters(tier, state, PageRequest.of(page, size))
				.map(SyncStateResponse::from));
	}
}
