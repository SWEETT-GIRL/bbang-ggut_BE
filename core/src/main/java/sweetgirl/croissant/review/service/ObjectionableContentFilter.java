package sweetgirl.croissant.review.service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import sweetgirl.croissant.review.code.ReviewErrorCode;
import sweetgirl.croissant.review.exception.ReviewException;

@Component
public class ObjectionableContentFilter {

	private static final Pattern KEYWORD_SEPARATOR_PATTERN = Pattern.compile("[\\s\\p{Punct}]+");
	private static final Pattern MOBILE_PHONE_PATTERN = Pattern
		.compile("(?<!\\d)(?:\\+82[-\\s.]?10|01[016789])[-\\s.]?\\d{3,4}[-\\s.]?\\d{4}(?!\\d)");
	private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern RESIDENT_REGISTRATION_NUMBER_PATTERN = Pattern
		.compile("(?<!\\d)\\d{6}[-\\s]?[1-8]\\d{6}(?!\\d)");

	// 초기 필터 사전은 코드 상수로 관리하고, 운영 고도화 시 DB 또는 설정 파일로 분리한다.
	private static final List<String> ABUSIVE_KEYWORDS = List.of(
		"개새끼",
		"새끼",
		"씨발",
		"시발",
		"ㅅㅂ",
		"병신",
		"븅신",
		"지랄",
		"등신",
		"멍청이",
		"미친놈",
		"미친년");
	private static final List<String> HATE_KEYWORDS = List.of(
		"장애인비하",
		"인종차별",
		"성소수자혐오",
		"외국인혐오",
		"여혐",
		"남혐",
		"틀딱",
		"김치녀",
		"한남충",
		"맘충");

	public void validate(String content) {
		if (containsObjectionableContent(content)) {
			throw new ReviewException(ReviewErrorCode.REVIEW_CONTENT_REJECTED);
		}
	}

	/** 리뷰 밖(빵 종류 이름 등)에서도 같은 판정을 쓴다 — 예외는 부르는 쪽 도메인 것으로 */
	public boolean containsObjectionableContent(String content) {
		if (content == null || content.isBlank()) {
			return false;
		}
		return containsPrivacyPattern(content) || containsRejectedKeyword(content);
	}

	private boolean containsPrivacyPattern(String content) {
		return MOBILE_PHONE_PATTERN.matcher(content).find()
			|| EMAIL_PATTERN.matcher(content).find()
			|| RESIDENT_REGISTRATION_NUMBER_PATTERN.matcher(content).find();
	}

	private boolean containsRejectedKeyword(String content) {
		String normalized = normalizeForKeyword(content);
		return ABUSIVE_KEYWORDS.stream().anyMatch(normalized::contains)
			|| HATE_KEYWORDS.stream().anyMatch(normalized::contains);
	}

	private String normalizeForKeyword(String content) {
		return KEYWORD_SEPARATOR_PATTERN.matcher(content.toLowerCase(Locale.ROOT)).replaceAll("");
	}
}
