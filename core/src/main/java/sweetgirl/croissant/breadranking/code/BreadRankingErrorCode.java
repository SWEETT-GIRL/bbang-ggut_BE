package sweetgirl.croissant.breadranking.code;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum BreadRankingErrorCode implements BaseErrorCode {

	BREAD_CATEGORY_NOT_FOUND(NOT_FOUND, "BREAD_CATEGORY_NOT_FOUND", "빵 종류를 찾을 수 없어요."),
	BREAD_CATEGORY_INVALID_NAME(BAD_REQUEST, "BREAD_CATEGORY_INVALID_NAME", "빵 종류 이름은 2~15자예요."),
	BREAD_CATEGORY_NAME_REJECTED(BAD_REQUEST, "BREAD_CATEGORY_NAME_REJECTED",
			"부적절하거나 개인정보로 보이는 표현이 들어 있어 만들 수 없어요."),
	BREAD_RANK_NOT_EATEN(BAD_REQUEST, "BREAD_RANK_NOT_EATEN", "빵로그를 남긴 가게만 순위에 담을 수 있어요."),
	BREAD_RANK_ENTRY_NOT_FOUND(NOT_FOUND, "BREAD_RANK_ENTRY_NOT_FOUND", "내 순위에 없는 가게예요."),
	BREAD_RANK_INVALID_RANK(BAD_REQUEST, "BREAD_RANK_INVALID_RANK", "등수는 1부터 지금 줄 수 + 1 사이여야 해요."),
	BREAD_RATING_NOT_FOUND(NOT_FOUND, "BREAD_RATING_NOT_FOUND", "이 가게에서 이 빵을 태그한 빵로그가 없어요.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
