package sweetgirl.croissant.course.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum CourseErrorCode implements BaseErrorCode {

	COURSE_NOT_FOUND(HttpStatus.NOT_FOUND, "COURSE404_1", "코스를 찾을 수 없습니다."),
	COURSE_DISPLAY_NAME_RETIRED(HttpStatus.GONE, "COURSE410_1", "빵투어 안 이름 짓기는 끝났습니다. 이제 회원 닉네임으로 보입니다."),
	COURSE_STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "COURSE404_2", "코스 가게를 찾을 수 없습니다."),
	COURSE_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "COURSE404_3", "코스 이미지를 찾을 수 없습니다."),
	COURSE_FORBIDDEN(HttpStatus.FORBIDDEN, "COURSE403_1", "코스에 대한 권한이 없습니다."),
	COURSE_STORE_ALREADY_EXISTS(HttpStatus.BAD_REQUEST, "COURSE400_1", "이미 코스에 추가된 가게입니다."),
	COURSE_SAVED_ALREADY(HttpStatus.BAD_REQUEST, "COURSE400_2", "이미 찜한 코스입니다."),
	COURSE_SAVED_NOT_FOUND(HttpStatus.NOT_FOUND, "COURSE404_4", "찜한 코스를 찾을 수 없습니다."),
	COURSE_FORK_NOT_PUBLIC(HttpStatus.BAD_REQUEST, "COURSE400_3", "공개 코스만 포크할 수 있습니다."),
	COURSE_FORK_NOT_MODIFIED(HttpStatus.BAD_REQUEST, "COURSE400_4", "원본 코스와 동일한 내용은 저장할 수 없습니다."),
	COURSE_ALREADY_PINNED(HttpStatus.BAD_REQUEST, "COURSE400_5", "이미 핀된 코스입니다."),
	COURSE_NOT_PINNED(HttpStatus.BAD_REQUEST, "COURSE400_6", "핀되지 않은 코스입니다."),
	COURSE_PIN_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "COURSE400_7", "핀은 최대 3개까지 설정할 수 있습니다."),
	COURSE_PIN_ORDER_CONFLICT(HttpStatus.BAD_REQUEST, "COURSE400_8", "해당 순위에 이미 핀된 코스가 있습니다."),
	COURSE_SAVE_SELF(HttpStatus.BAD_REQUEST, "COURSE400_9", "자신의 코스는 찜할 수 없습니다."),
	COURSE_STORE_ORDER_INVALID(HttpStatus.BAD_REQUEST, "COURSE400_10", "코스의 모든 가게를 중복 없이 전달해야 합니다."),
	COURSE_STORE_DAY_INVALID(HttpStatus.BAD_REQUEST, "COURSE400_11", "일차 정보가 올바르지 않습니다."),
	COURSE_DATES_INVALID(HttpStatus.BAD_REQUEST, "COURSE400_12", "여행 날짜가 올바르지 않습니다."),
	COURSE_VISIT_RECORD_MISMATCH(HttpStatus.BAD_REQUEST, "COURSE400_13", "그 가게의 기록이 아닙니다."),
	COURSE_VISIT_NOT_FOUND(HttpStatus.NOT_FOUND, "COURSE404_5", "방문 표시를 찾을 수 없습니다."),
	COURSE_INVITE_INVALID(HttpStatus.NOT_FOUND, "COURSE404_6", "유효하지 않은 초대예요."),
	COURSE_INVITE_EXPIRED(HttpStatus.BAD_REQUEST, "COURSE400_14", "초대 링크가 만료됐어요. 새 링크를 받아주세요."),
	COURSE_MEMBER_LIMIT(HttpStatus.BAD_REQUEST, "COURSE400_15", "빵투어 인원이 다 찼어요."),
	COURSE_COLLAB_CANNOT_BE_PUBLIC(HttpStatus.BAD_REQUEST, "COURSE400_16", "같이 편집 중인 빵투어는 공개할 수 없어요."),
	COURSE_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "COURSE404_7", "이 빵투어의 일행이 아니에요."),
	COURSE_NO_SUCCESSOR(HttpStatus.BAD_REQUEST, "COURSE400_17", "넘겨줄 일행이 없어요. 빵투어를 삭제해주세요."),
	COURSE_DAY_COUNT_INVALID(HttpStatus.BAD_REQUEST, "COURSE400_18", "일차 수는 줄일 수 없어요."),
	COURSE_CATEGORY_INVALID(HttpStatus.BAD_REQUEST, "COURSE400_19", "분류는 그룹과 값을 함께 보내야 합니다."),
	COURSE_STALE(HttpStatus.CONFLICT, "COURSE409_1", "일행이 방금 수정했어요. 다시 불러올게요."),
	// 앱 안 일행 초대(팔로잉 · 팔로워에서 고름)
	COURSE_INVITEE_NOT_FOUND(HttpStatus.NOT_FOUND, "COURSE404_8", "초대할 회원을 찾을 수 없어요."),
	COURSE_INVITEE_SELF(HttpStatus.BAD_REQUEST, "COURSE400_20", "나 자신은 초대할 수 없어요."),
	COURSE_INVITEE_NOT_RELATED(HttpStatus.BAD_REQUEST, "COURSE400_21", "팔로우 관계인 사람만 초대할 수 있어요."),
	COURSE_INVITEE_BLOCKED(HttpStatus.FORBIDDEN, "COURSE403_2", "차단 관계인 사람은 초대할 수 없어요."),
	COURSE_MEMBER_INVITE_NOT_FOUND(HttpStatus.NOT_FOUND, "COURSE404_9", "받은 초대가 없어요."),
	COURSE_MEMO_SEQ_INVALID(HttpStatus.BAD_REQUEST, "COURSE400_22", "메모 순서 값이 올바르지 않아요. 기기 시간을 확인해주세요.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
