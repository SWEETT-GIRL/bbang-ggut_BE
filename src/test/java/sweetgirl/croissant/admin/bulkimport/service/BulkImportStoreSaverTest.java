package sweetgirl.croissant.admin.bulkimport.service;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class BulkImportStoreSaverTest {

	@Mock
	StoreRepository storeRepository;

	@InjectMocks
	BulkImportStoreSaver saver;

	@Test
	@DisplayName("category_name에 '양식'이 있으면 'WESTERN' 문자열로 저장된다")
	void save_passesWesternCategory_whenCategoryNameContainsYangsik() {
		KakaoPlace place = new KakaoPlace(
			"kakao-1", "테스트빵집", "서울 강남구 역삼동 1", "서울 강남구 테헤란로 1",
			"음식점 > 양식 > 베이커리", "FD6", "02-1234-5678", "127.0", "37.5");

		saver.save(place);

		verify(storeRepository).insertIfNotExistsByKakaoPlaceId(
			eq("테스트빵집"), anyString(), anyString(), anyString(), anyString(),
			anyDouble(), anyDouble(), eq("02-1234-5678"), eq("kakao-1"), eq("WESTERN"));
	}

	@Test
	@DisplayName("category_name에 '양식'이 없으면 null이 저장된다")
	void save_passesNullCategory_whenCategoryNameDoesNotContainYangsik() {
		KakaoPlace place = new KakaoPlace(
			"kakao-2", "도넛집", "서울 강남구 역삼동 2", "서울 강남구 테헤란로 2",
			"음식점 > 간식 > 도넛", "FD6", null, "127.0", "37.5");

		saver.save(place);

		verify(storeRepository).insertIfNotExistsByKakaoPlaceId(
			eq("도넛집"), anyString(), anyString(), anyString(), anyString(),
			anyDouble(), anyDouble(), eq(null), eq("kakao-2"), eq(null));
	}

	@Test
	@DisplayName("roadAddressName이 비어있으면 addressName을 사용한다")
	void save_fallsBackToAddressName_whenRoadAddressIsBlank() {
		KakaoPlace place = new KakaoPlace(
			"kakao-3", "빵집", "서울 강남구 역삼동 3", "",
			"음식점 > 간식", "FD6", null, "127.0", "37.5");

		saver.save(place);

		verify(storeRepository).insertIfNotExistsByKakaoPlaceId(
			eq("빵집"), eq("서울 강남구 역삼동 3"), anyString(), anyString(), anyString(),
			anyDouble(), anyDouble(), eq(null), eq("kakao-3"), eq(null));
	}
}
