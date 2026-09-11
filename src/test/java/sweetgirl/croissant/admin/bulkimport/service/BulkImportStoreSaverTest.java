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
	@DisplayName("카카오 경로의 잎을 보고 분류한다 — '음식점 > 양식 > 베이커리' 는 양식집이 아니라 BAKERY")
	void save_classifiesByLeafNode_notByWesternKeyword() {
		KakaoPlace place = new KakaoPlace(
			"kakao-1", "테스트빵집", "서울 강남구 역삼동 1", "서울 강남구 테헤란로 1",
			"음식점 > 양식 > 베이커리", "FD6", "02-1234-5678", "127.0", "37.5");

		saver.save(place);

		verify(storeRepository).insertIfNotExistsByKakaoPlaceId(
			eq("테스트빵집"), anyString(), anyString(), anyString(), anyString(),
			anyDouble(), anyDouble(), eq("02-1234-5678"), eq("kakao-1"), eq("BAKERY"));
	}

	@Test
	@DisplayName("잎이 우리 분류에 있으면 그대로 들어간다 — '음식점 > 간식 > 도넛' 은 DONUT")
	void save_passesMappedLeafCategory() {
		KakaoPlace place = new KakaoPlace(
			"kakao-2", "도넛집", "서울 강남구 역삼동 2", "서울 강남구 테헤란로 2",
			"음식점 > 간식 > 도넛", "FD6", null, "127.0", "37.5");

		saver.save(place);

		verify(storeRepository).insertIfNotExistsByKakaoPlaceId(
			eq("도넛집"), anyString(), anyString(), anyString(), anyString(),
			anyDouble(), anyDouble(), eq(null), eq("kakao-2"), eq("DONUT"));
	}

	@Test
	@DisplayName("어느 마디도 매핑에 없으면 OTHER — 값이 아예 없는 null(미수집)과 구분한다")
	void save_passesOther_whenNoNodeMatches() {
		KakaoPlace place = new KakaoPlace(
			"kakao-4", "정체불명", "서울 강남구 역삼동 4", "서울 강남구 테헤란로 4",
			"음식점 > 간식", "FD6", null, "127.0", "37.5");

		saver.save(place);

		verify(storeRepository).insertIfNotExistsByKakaoPlaceId(
			eq("정체불명"), anyString(), anyString(), anyString(), anyString(),
			anyDouble(), anyDouble(), eq(null), eq("kakao-4"), eq("OTHER"));
	}

	@Test
	@DisplayName("카테고리를 못 받으면 null 이 저장된다")
	void save_passesNullCategory_whenCategoryNameIsMissing() {
		KakaoPlace place = new KakaoPlace(
			"kakao-5", "이름만아는집", "서울 강남구 역삼동 5", "서울 강남구 테헤란로 5",
			null, "FD6", null, "127.0", "37.5");

		saver.save(place);

		verify(storeRepository).insertIfNotExistsByKakaoPlaceId(
			eq("이름만아는집"), anyString(), anyString(), anyString(), anyString(),
			anyDouble(), anyDouble(), eq(null), eq("kakao-5"), eq(null));
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
			anyDouble(), anyDouble(), eq(null), eq("kakao-3"), eq("OTHER"));
	}
}
