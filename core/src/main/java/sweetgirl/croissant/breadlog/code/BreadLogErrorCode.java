package sweetgirl.croissant.breadlog.code;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum BreadLogErrorCode implements BaseErrorCode {

	TUTORIAL_CATALOG_CONFLICT(HttpStatus.CONFLICT, "TUTORIAL_CATALOG_CONFLICT",
			"다른 관리자가 발행 상태를 바꿨어요. 새로고침 후 다시 확인해 주세요."),

	TUTORIAL_UNAVAILABLE(BAD_REQUEST, "TUTORIAL_UNAVAILABLE", "첫 빵로그 체험을 준비 중이에요. 잠시 후 다시 시도해 주세요."),
	INVALID_TUTORIAL(BAD_REQUEST, "INVALID_TUTORIAL", "체험 내용을 확인해 주세요."),
	TUTORIAL_EDIT_RESTRICTED(BAD_REQUEST, "TUTORIAL_EDIT_RESTRICTED", "체험 기록은 메모·평가·쌓을 빵만 수정할 수 있어요."),

	BREAD_RECORD_NOT_FOUND(NOT_FOUND, "BREAD_RECORD_NOT_FOUND", "빵 기록을 찾을 수 없습니다."),
	BREAD_RECORD_ACCESS_DENIED(FORBIDDEN, "BREAD_RECORD_ACCESS_DENIED", "빵 기록을 수정하거나 삭제할 권한이 없습니다."),
	BREAD_RECORD_NOT_VISIBLE(FORBIDDEN, "BREAD_RECORD403_NOT_VISIBLE", "볼 수 없는 빵로그예요."),
	// 같이 간 사람 태그 — 팔로우 관계(팔로잉 · 팔로워) 또는 빵투어 일행만, 하루 5명
	COMPANION_SELF(BAD_REQUEST, "COMPANION400_SELF", "나 자신은 같이 간 사람으로 태그할 수 없어요."),
	COMPANION_NOT_RELATED(BAD_REQUEST, "COMPANION400_NOT_RELATED", "팔로우 관계이거나 빵투어 일행인 사람만 태그할 수 있어요."),
	COMPANION_REMOVED(BAD_REQUEST, "COMPANION400_REMOVED", "태그를 뺀 사람은 같은 빵로그에 다시 태그할 수 없어요."),
	COMPANION_MEMBER_NOT_FOUND(NOT_FOUND, "COMPANION404_MEMBER", "태그할 회원을 찾을 수 없어요."),
	COMPANION_BLOCKED(FORBIDDEN, "COMPANION403_BLOCKED", "차단 관계인 사람은 태그할 수 없어요."),
	COMPANION_DAILY_LIMIT(TOO_MANY_REQUESTS, "COMPANION429_DAILY_LIMIT", "오늘은 더 태그할 수 없어요. 내일 다시 해 주세요."),
	COMPANION_NOT_TAGGED(NOT_FOUND, "COMPANION404_NOT_TAGGED", "이 빵로그에 태그되어 있지 않아요."),
	IMPORT_NOT_COMPANION(FORBIDDEN, "IMPORT403_NOT_COMPANION", "같이 간 사람으로 태그된 빵로그만 가져올 수 있어요."),
	DUPLICATE_PHOTO_ORDER(BAD_REQUEST, "BREAD_RECORD_DUPLICATE_PHOTO_ORDER", "사진 순서는 중복될 수 없습니다."),
	TAG_PHOTO_NOT_FOUND(BAD_REQUEST, "BREAD_RECORD_TAG_PHOTO_NOT_FOUND", "태그가 가리키는 사진을 찾을 수 없습니다."),
	INVALID_TAG_POSITION(BAD_REQUEST, "BREAD_RECORD_INVALID_TAG_POSITION", "태그 좌표는 0부터 1 사이여야 합니다."),
	INVALID_LOVED_BREAD(BAD_REQUEST, "BREAD_RECORD_INVALID_LOVED_BREAD", "가장 맛있었던 빵은 사진 태그 중에서 선택해 주세요."),
	INVALID_IMAGE_MIME_TYPE(BAD_REQUEST, "BREAD_RECORD_INVALID_IMAGE_MIME_TYPE", "지원하지 않는 빵 기록 이미지 형식입니다."),
	INVALID_IMAGE_KEY(BAD_REQUEST, "BREAD_RECORD_INVALID_IMAGE_KEY", "유효하지 않은 빵 기록 이미지 키입니다."),
	INVALID_PHOTO_REFERENCE(BAD_REQUEST, "BREAD_RECORD_INVALID_PHOTO_REFERENCE", "교체할 원본 사진을 확인해 주세요."),
	INVALID_DATE_RANGE(BAD_REQUEST, "BREAD_RECORD_INVALID_DATE_RANGE", "조회 시작 시각은 종료 시각보다 빨라야 합니다."),
	INVALID_PAGE_REQUEST(BAD_REQUEST, "BREAD_RECORD_INVALID_PAGE_REQUEST", "페이지는 0 이상, 페이지 크기는 1부터 100 사이여야 합니다."),
	PHOTO_KEY_COUNT_MISMATCH(BAD_REQUEST, "BREAD_RECORD_PHOTO_KEY_COUNT_MISMATCH", "사진과 사진 키의 개수가 같아야 합니다."),
	UNREADABLE_PHOTO(BAD_REQUEST, "BREAD_RECORD_UNREADABLE_PHOTO", "사진을 읽을 수 없습니다."),
	PHOTO_TOO_LARGE(BAD_REQUEST, "BREAD_RECORD_PHOTO_TOO_LARGE", "분석할 사진이 너무 큽니다."),
	PHOTO_ANALYZE_RATE_LIMITED(TOO_MANY_REQUESTS, "BREAD_RECORD_PHOTO_ANALYZE_RATE_LIMITED",
			"사진 분석 요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요."),
	INVALID_RANKING_REGION(BAD_REQUEST, "RANKING_INVALID_REGION", "위치를 지역으로 확인하지 못했어요."),
	RANKING_REGION_NOT_SET(BAD_REQUEST, "RANKING_REGION_NOT_SET", "내 동네를 먼저 인증해 주세요.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
