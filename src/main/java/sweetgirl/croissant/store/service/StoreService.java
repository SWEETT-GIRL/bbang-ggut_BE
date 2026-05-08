package sweetgirl.croissant.store.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.dto.StoreDetailResponse;
import sweetgirl.croissant.store.dto.SubscribedStoreResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

	private final StoreRepository storeRepository;
	private final StoreBusinessHourRepository storeBusinessHourRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final SubscribedStoreRepository subscribedStoreRepository;

	public StoreDetailResponse getStoreDetail(Long storeId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		List<StoreBusinessHour> businessHours = storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(storeId);
		List<StoreImageFile> images = storeImageFileRepository.findByStoreId(storeId);
		return StoreDetailResponse.of(store, businessHours, images);
	}

	public List<SubscribedStoreResponse> getSubscribedStores(Member member) {
		return subscribedStoreRepository.findAllByMember(member)
			.stream()
			.map(SubscribedStoreResponse::from)
			.toList();
	}
}
