package sweetgirl.croissant.external.opensearch.exception;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum OpenSearchErrorCode implements BaseErrorCode {

	BULK_INDEX_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "OS5001", "OpenSearch 벌크 색인 중 오류가 발생했습니다."),
	REINDEX_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "OS4091", "재색인 작업이 이미 진행 중입니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
