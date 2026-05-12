package sweetgirl.croissant.store.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.store.enums.District;
import sweetgirl.croissant.store.repository.StoreRepository;

@Component
@RequiredArgsConstructor
public class IndividualStoreSaver {
	private final StoreRepository storeRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW) // 각 호출마다 독립적인 트랜잭션 생성
	public void save(KakaoPlace place) {
		String address = place.roadAddressName() != null && !place.roadAddressName().isBlank()
			? place.roadAddressName() : place.addressName();

		District district = District.fromAddress(address).orElse(null);

		storeRepository.insertIfNotExistsByKakaoPlaceId(
			place.placeName(),
			address,
			district != null ? district.name() : null,
			Double.parseDouble(place.x()),
			Double.parseDouble(place.y()),
			place.phone(),
			place.id());
	}
}
