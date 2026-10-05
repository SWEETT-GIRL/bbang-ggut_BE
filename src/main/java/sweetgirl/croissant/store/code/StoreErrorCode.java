package sweetgirl.croissant.store.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum StoreErrorCode implements BaseErrorCode {

	STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE404", "빵집을 찾을 수 없습니다."),
	INVALID_DISTRICT(HttpStatus.BAD_REQUEST, "STORE400_2", "유효하지 않은 지역입니다."),
	ALREADY_SUBSCRIBED(HttpStatus.CONFLICT, "STORE409_1", "이미 구독 중인 빵집입니다."),
	NOT_SUBSCRIBED(HttpStatus.BAD_REQUEST, "STORE400_1", "구독 중이지 않은 빵집입니다."),
	NAVER_ENRICH_ALREADY_RUNNING(HttpStatus.CONFLICT, "STORE409_2", "네이버 일괄 매칭이 이미 실행 중입니다."),
	NAVER_ENRICH_STORE_IN_PROGRESS(HttpStatus.CONFLICT, "STORE409_3", "해당 가게의 네이버 매칭이 이미 진행 중입니다."),
	NAVER_PLACE_ID_DUPLICATE(HttpStatus.CONFLICT, "STORE409_4", "다른 가게가 이미 사용 중인 네이버 placeId 입니다."),
	POPULARITY_REFRESH_ALREADY_RUNNING(HttpStatus.CONFLICT, "STORE409_5", "가게 인기도 갱신이 이미 실행 중입니다."),
	TRENDING_BASELINE_REFRESH_ALREADY_RUNNING(HttpStatus.CONFLICT, "STORE409_6", "가게 평소 수준 갱신이 이미 실행 중입니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
