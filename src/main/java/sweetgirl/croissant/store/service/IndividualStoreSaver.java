package sweetgirl.croissant.store.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.store.AddressParser;
import sweetgirl.croissant.store.repository.StoreRepository;

@Component
@RequiredArgsConstructor
public class IndividualStoreSaver {
	private final StoreRepository storeRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void save(KakaoPlace place) {
		String address = place.roadAddressName() != null && !place.roadAddressName().isBlank()
			? place.roadAddressName() : place.addressName();

		// region 파싱은 도로명주소가 아닌 지번주소(addressName) 기준
		String[] addr = AddressParser.parse(place.addressName());

		storeRepository.insertIfNotExistsByKakaoPlaceId(
			place.placeName(),
			address,
			addr[0], addr[1], addr[2],
			Double.parseDouble(place.x()),
			Double.parseDouble(place.y()),
			place.phone(),
			place.id());
	}
}
