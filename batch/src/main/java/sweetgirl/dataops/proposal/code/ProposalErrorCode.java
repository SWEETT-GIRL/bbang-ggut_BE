package sweetgirl.dataops.proposal.code;

import static org.springframework.http.HttpStatus.*;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.dataops.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum ProposalErrorCode implements BaseErrorCode {

	PROPOSAL_NOT_FOUND(NOT_FOUND, "PROPOSAL_NOT_FOUND", "변경 제안을 찾을 수 없습니다."),
	PROPOSAL_STATUS_TRANSITION_NOT_ALLOWED(
			BAD_REQUEST, "PROPOSAL_STATUS_TRANSITION_NOT_ALLOWED", "PENDING 상태의 제안만 처리할 수 있습니다."),
	PROPOSAL_NOT_CLAIMABLE(
			CONFLICT, "PROPOSAL_NOT_CLAIMABLE",
			"이미 반영 중이거나 처리가 끝난 제안입니다 — 중복 승인 요청은 운영에 쓰지 않습니다."),
	PROPOSAL_TARGET_UNRESOLVED(CONFLICT,"PROPOSAL_TARGET_UNRESOLVED",
			"같은 가게·대상의 반영 결과가 미확정입니다 — 실제 반영 근거를 대사하기 전에는 새 제안을 반영할 수 없습니다."),
	PROPOSAL_TARGET_UNSUPPORTED(
			INTERNAL_SERVER_ERROR, "PROPOSAL_TARGET_UNSUPPORTED", "지원하지 않는 반영 대상(target)입니다."),
	PROPOSAL_VALUE_INVALID(
			INTERNAL_SERVER_ERROR, "PROPOSAL_VALUE_INVALID", "제안 값을 해석할 수 없습니다 — 운영에는 쓰지 않았습니다."),
	PROPOSAL_APPLY_UNKNOWN(
			INTERNAL_SERVER_ERROR, "PROPOSAL_APPLY_UNKNOWN",
			"운영 반영 결과를 확인하지 못했습니다 — 제안은 UNKNOWN 으로 남고 같은 대상을 다시 쓰지 않습니다."),
	LOCATION_EVIDENCE_REQUIRED(
			BAD_REQUEST, "LOCATION_EVIDENCE_REQUIRED",
			"검증된 지역 근거가 없어 위치 제안을 보류합니다 — 제안은 PENDING 으로 남고, 근거가 붙으면 승인할 수 있습니다."),
	MENU_EVIDENCE_REQUIRED(
			BAD_REQUEST, "MENU_EVIDENCE_REQUIRED",
			"최신의 완전한 메뉴 부재 근거가 없어 삭제 제안을 보류합니다 — 제안은 PENDING 으로 남습니다."),
	SERVING_VALUE_DRIFTED(
			CONFLICT, "SERVING_VALUE_DRIFTED",
			"운영 값이 제안 당시와 달라 반영하지 않았습니다 — 운영에는 쓰지 않았고 제안은 FAILED 로 남습니다. 새 스냅샷으로 다시 판정하세요."),
	PROPOSAL_APPLY_FAILED(
			INTERNAL_SERVER_ERROR, "PROPOSAL_APPLY_FAILED", "운영 반영에 실패했습니다 — apply_log 를 확인하세요."),
	MENU_CONFLICTS_USER_MENU(
			CONFLICT, "MENU_CONFLICTS_USER_MENU",
			"같은 이름의 사용자 등록 메뉴가 있어 반영하지 않았습니다 — 운영에는 쓰지 않았고 제안은 FAILED 로 남습니다."),
	MENU_CONFLICTS_DISTINCT_NAME(
			CONFLICT, "MENU_CONFLICTS_DISTINCT_NAME",
			"운영 정규화로는 구분되지 않는 다른 이름의 메뉴가 이미 있어 반영하지 않았습니다 — 운영에는 쓰지 않았고 제안은 FAILED 로 남습니다."),
	MENU_CATEGORY_CHANGED(
			CONFLICT, "MENU_CATEGORY_CHANGED",
			"제안 이후 메뉴 종류가 바뀌었거나(삭제·사용자 등록 메뉴 포함) 대상 종류가 운영에 없어 반영하지 않았습니다 — 운영에는 쓰지 않았고 제안은 FAILED 로 남습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
