package sweetgirl.dataops.snapshot.code;

import static org.springframework.http.HttpStatus.*;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.dataops.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum SnapshotErrorCode implements BaseErrorCode {

	SNAPSHOT_NOT_FOUND(NOT_FOUND, "SNAPSHOT_NOT_FOUND", "스냅샷을 찾을 수 없습니다."),
	SNAPSHOT_SYNC_STATE_NOT_FOUND(
			NOT_FOUND, "SNAPSHOT_SYNC_STATE_NOT_FOUND",
			"제출 대상의 동기화 상태(store×source)가 없습니다 — 시딩 여부를 확인하세요."),
	SNAPSHOT_HASH_FAILED(INTERNAL_SERVER_ERROR, "SNAPSHOT_HASH_FAILED", "페이로드 해시 계산에 실패했습니다."),
	OBSERVATION_CONFLICT(CONFLICT, "OBSERVATION_CONFLICT", "같은 관측 ID에 다른 페이로드 또는 식별 정보가 제출되었습니다."),
	OBSERVATION_INVALID(BAD_REQUEST, "OBSERVATION_INVALID", "관측 ID, 외부 ID, 오프셋 포함 관측 시각을 함께 제출해야 합니다."),
	OBSERVATION_TARGET_MISMATCH(CONFLICT, "OBSERVATION_TARGET_MISMATCH", "관측 대상이 현재 동기화 대상과 일치하지 않습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
