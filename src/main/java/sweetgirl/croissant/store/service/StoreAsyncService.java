package sweetgirl.croissant.store.service;

import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.common.enums.District;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.store.repository.StoreRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoreAsyncService {

	private final StoreRepository storeRepository;

	@Async
	public void saveKakaoPlaces(List<KakaoPlace> places) {
		for (KakaoPlace place : places) {
			try {
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
			} catch (Exception e) {
				log.error("카카오 장소 저장 중 에러 발생 - 장소명: {}, 사유: {}", place.placeName(), e.getMessage());
			}
		}
	}
}
