package sweetgirl.croissant.store.service;

import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoreAsyncService {

	private final IndividualStoreSaver individualStoreSaver;

	@Async
	public void saveKakaoPlaces(List<KakaoPlace> places) {
		for (KakaoPlace place : places) {
			try {
				individualStoreSaver.save(place);
			} catch (Exception e) {
				log.error("카카오 장소 저장 중 에러 발생 - 장소명: {}, 사유: {}", place.placeName(), e.getMessage());
			}
		}
	}
}
