package sweetgirl.dataops.serving.code;

import static org.springframework.http.HttpStatus.*;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.dataops.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum ServingErrorCode implements BaseErrorCode {

	PROD_DB_NOT_CONFIGURED(
			SERVICE_UNAVAILABLE, "PROD_DB_NOT_CONFIGURED",
			"운영 DB가 설정되지 않았습니다 — .env 의 PROD_DB_URL/USERNAME/PASSWORD 를 확인하세요.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
