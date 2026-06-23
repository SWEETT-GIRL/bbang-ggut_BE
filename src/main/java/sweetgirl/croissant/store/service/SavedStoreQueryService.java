package sweetgirl.croissant.store.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.store.dto.response.SavedStoreResponse;
import sweetgirl.croissant.store.repository.SavedStoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SavedStoreQueryService {

	private final SavedStoreRepository savedStoreRepository;

	public List<SavedStoreResponse> getSavedStores(Long memberId) {
		return savedStoreRepository.findAllByMemberId(memberId).stream()
			.map(SavedStoreResponse::from)
			.toList();
	}
}
