package sweetgirl.croissant.admin.bulkimport.code;

import static org.springframework.http.HttpStatus.*;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum BulkImportErrorCode implements BaseErrorCode {

	BULK_IMPORT_ALREADY_RUNNING(CONFLICT, "BULK409_1", "Bulk import가 이미 실행 중입니다."),
	DONG_LIST_NOT_FOUND(INTERNAL_SERVER_ERROR, "BULK500_1", "동 리스트 CSV 파일을 찾을 수 없습니다."),
	DONG_LIST_PARSE_ERROR(INTERNAL_SERVER_ERROR, "BULK500_2", "동 리스트 CSV 파싱에 실패했습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
