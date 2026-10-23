package sweetgirl.croissant.comment.service;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.comment.code.CommentErrorCode;
import sweetgirl.croissant.comment.entity.BreadRecordComment;
import sweetgirl.croissant.comment.exception.CommentException;
import sweetgirl.croissant.moderation.service.ForbiddenWordFilter;

/**
 * 올라가기 전 걸러내기(애플 1.2 · 구글 UGC) — 길이 · 줄바꿈 · 금칙어 · 링크 · 전화번호. 사유 코드가 다 달라 앱이 문구를 고른다.
 * 공개 빵로그 후기도 금칙어는 같이 본다.
 */
@Component
@RequiredArgsConstructor
public class CommentContentPolicy {

	/** http(s)://, www., 또는 도메인.tld/… 모양. 이메일은 아니다. */
	static final Pattern LINK = Pattern.compile(
		"(?i)(https?://|www\\.|[a-z0-9-]+\\.(com|net|org|kr|co|io|me|link|app|shop|site|xyz)(/|\\b))");
	/** 리뷰 필터(ObjectionableContentFilter)와 같은 휴대폰 모양. */
	static final Pattern PHONE = Pattern.compile(
		"(?<!\\d)(?:\\+82[-\\s.]?10|01[016789])[-\\s.]?\\d{3,4}[-\\s.]?\\d{4}(?!\\d)");

	private final ForbiddenWordFilter forbiddenWordFilter;

	public void validateComment(String content) {
		if (content == null || content.isBlank() || content.length() > BreadRecordComment.CONTENT_MAX_LENGTH
			|| content.chars().filter(ch -> ch == '\n').count() > BreadRecordComment.MAX_LINE_BREAKS) {
			throw new CommentException(CommentErrorCode.COMMENT_TOO_LONG);
		}
		validateWords(content);
		if (LINK.matcher(content).find()) {
			throw new CommentException(CommentErrorCode.COMMENT_LINK);
		}
		if (PHONE.matcher(content).find()) {
			throw new CommentException(CommentErrorCode.COMMENT_PHONE);
		}
	}

	/** 금칙어만 본다 — 공개 빵로그 후기용. */
	public void validateWords(String text) {
		if (forbiddenWordFilter.containsForbidden(text)) {
			throw new CommentException(CommentErrorCode.COMMENT_FORBIDDEN_WORD);
		}
	}
}
