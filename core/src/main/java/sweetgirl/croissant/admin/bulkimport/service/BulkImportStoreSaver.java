package sweetgirl.croissant.admin.bulkimport.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.store.AddressParser;
import sweetgirl.croissant.store.enums.StoreCategory;
import sweetgirl.croissant.store.event.StoreSearchNameChangedEvent;
import sweetgirl.croissant.store.repository.StoreRepository;

@Component
@RequiredArgsConstructor
public class BulkImportStoreSaver {

	private final StoreRepository storeRepository;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void save(KakaoPlace place) {
		String address = place.roadAddressName() != null && !place.roadAddressName().isBlank()
			? place.roadAddressName() : place.addressName();

		String[] addr = AddressParser.parse(place.addressName());
		StoreCategory category = StoreCategory.fromExternalName(place.categoryName());
		String categoryName = category != null ? category.name() : null;

		storeRepository.insertIfNotExistsByKakaoPlaceId(
			place.placeName(),
			address,
			addr[0], addr[1], addr[2],
			Double.parseDouble(place.x()),
			Double.parseDouble(place.y()),
			place.phone(),
			place.id(),
			categoryName);
		// 가게마다 REQUIRES_NEW 로 커밋하므로 무효화도 가게마다 커밋 뒤에 돈다
		eventPublisher.publishEvent(StoreSearchNameChangedEvent.of(place.placeName()));
	}
}
