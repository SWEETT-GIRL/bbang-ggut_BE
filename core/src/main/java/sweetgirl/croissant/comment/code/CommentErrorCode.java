package sweetgirl.croissant.comment.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

/** 앱이 사유 코드로 문구를 고르므로(FE 02-C5~C8 · 08-D1 · D3) 코드 문자열은 그 화면이 기대하는 값 그대로다. */
@Getter
@AllArgsConstructor
public enum CommentErrorCode implements BaseErrorCode {

	COMMENT_TOO_LONG(HttpStatus.BAD_REQUEST, "COMMENT400_TOO_LONG", "댓글은 200자, 줄바꿈은 3줄까지예요."),
	COMMENT_FORBIDDEN_WORD(HttpStatus.BAD_REQUEST, "FORBIDDEN_WORD", "쓸 수 없는 말이 들어 있어요."),
	COMMENT_LINK(HttpStatus.BAD_REQUEST, "LINK", "링크는 넣을 수 없어요."),
	COMMENT_PHONE(HttpStatus.BAD_REQUEST, "PHONE", "전화번호는 넣을 수 없어요."),
	COMMENT_TOO_FAST(HttpStatus.TOO_MANY_REQUESTS, "TOO_FAST", "너무 빨리 쓰고 있어요. 잠시 뒤 다시 써 주세요."),
	COMMUNITY_AGREEMENT_REQUIRED(HttpStatus.FORBIDDEN, "COMMUNITY_AGREEMENT_REQUIRED", "커뮤니티 규칙에 먼저 동의해 주세요."),
	COMMENT_MENTION_LIMIT(HttpStatus.BAD_REQUEST, "COMMENT400_MENTION_LIMIT", "한 댓글에 3명까지 언급할 수 있어요."),
	COMMENT_MENTION_INVALID(HttpStatus.BAD_REQUEST, "COMMENT400_MENTION_INVALID", "언급할 수 없는 사람이에요."),
	COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMENT404", "댓글을 찾을 수 없어요."),
	COMMENT_DELETE_DENIED(HttpStatus.FORBIDDEN, "COMMENT403_DELETE", "내 댓글이거나 내 빵로그의 댓글만 지울 수 있어요.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
