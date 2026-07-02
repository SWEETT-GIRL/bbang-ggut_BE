package sweetgirl.croissant.admin.code;

import static org.springframework.http.HttpStatus.*;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum AdminErrorCode implements BaseErrorCode {

	REPORT_NOT_FOUND(NOT_FOUND, "ADMIN404_1", "제보를 찾을 수 없습니다."),
	REPORT_ALREADY_REVIEWED(CONFLICT, "ADMIN409_1", "이미 처리된 제보입니다."),
	REPORT_MODERATION_NOT_ALLOWED(CONFLICT, "ADMIN409_2", "승인된 사용자 신고에만 moderation action을 적용할 수 있습니다."),
	REPORT_INVALID_ACTION(BAD_REQUEST, "ADMIN400_1", "action은 ACCEPT 또는 REJECT여야 합니다."),
	REPORT_INVALID_KIND(
			BAD_REQUEST, "ADMIN400_2",
			"kind는 STORE_REPORT, STORE_ADD_REPORT, MENU_ADD_REPORT, ABUSE_REPORT 중 하나여야 합니다."),
	NAVER_ENRICH_INVALID_PARTITION(BAD_REQUEST, "ADMIN400_3", "partition은 P1, P2, P3 중 하나여야 합니다."),
	REPORT_INVALID_MODERATION_ACTION(
			BAD_REQUEST, "ADMIN400_4",
			"moderation action은 NONE, HIDE_REVIEW, DELETE_REVIEW, RESTORE_REVIEW 중 하나여야 합니다."),
	REPORT_TARGET_TYPE_MISMATCH(BAD_REQUEST, "ADMIN400_5", "신고 대상 타입에 적용할 수 없는 moderation action입니다."),
	MEMBER_RESTRICTION_INVALID_TYPE(
			BAD_REQUEST,
			"ADMIN400_6",
			"restriction type은 WARNING, WRITE_RESTRICTED, PROFILE_RESTRICTED, SUSPENDED 중 하나여야 합니다."),
	MEMBER_ALREADY_BANNED(CONFLICT, "ADMIN409_4", "이미 강퇴된 회원입니다."),
	MEMBER_RESTRICTION_INVALID_DURATION(BAD_REQUEST, "ADMIN400_7", "durationDays는 1 이상이어야 합니다."),
	APPEAL_INVALID_ACTION(BAD_REQUEST, "ADMIN400_8", "action은 ACCEPT 또는 REJECT여야 합니다."),
	APPEAL_INVALID_STATUS(BAD_REQUEST, "ADMIN400_9", "올바른 이의제기 상태가 아닙니다."),
	REPORT_TARGET_NOT_FOUND(NOT_FOUND, "ADMIN404_2", "조치 대상을 찾을 수 없습니다."),
	APPEAL_NOT_FOUND(NOT_FOUND, "ADMIN404_3", "이의제기를 찾을 수 없습니다."),
	APPEAL_ALREADY_REVIEWED(CONFLICT, "ADMIN409_3", "이미 처리된 이의제기입니다."),
	REPORT_PAYLOAD_INVALID(INTERNAL_SERVER_ERROR, "ADMIN500_1", "제보 데이터 파싱에 실패했습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
