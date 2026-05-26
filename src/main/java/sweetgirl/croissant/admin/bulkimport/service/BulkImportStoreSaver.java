package sweetgirl.croissant.admin.bulkimport.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.bulkimport.code.StoreCategoryDeriver;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.store.AddressParser;
import sweetgirl.croissant.store.enums.StoreCategory;
import sweetgirl.croissant.store.repository.StoreRepository;

@Component
@RequiredArgsConstructor
public class BulkImportStoreSaver {

	private final StoreRepository storeRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void save(KakaoPlace place) {
		String address = place.roadAddressName() != null && !place.roadAddressName().isBlank()
			? place.roadAddressName() : place.addressName();

		String[] addr = AddressParser.parse(place.addressName());
		StoreCategory category = StoreCategoryDeriver.derive(place.categoryName());
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
	}
}
