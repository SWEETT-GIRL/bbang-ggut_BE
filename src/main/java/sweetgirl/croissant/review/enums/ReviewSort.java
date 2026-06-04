package sweetgirl.croissant.review.enums;

import java.util.Locale;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.review.code.ReviewErrorCode;
import sweetgirl.croissant.review.exception.ReviewException;

@Getter
@RequiredArgsConstructor
public enum ReviewSort {
	RECENT("recent"),
	LIKED("liked");

	private final String value;

	public static ReviewSort from(String value) {
		if (value == null) {
			throw new ReviewException(ReviewErrorCode.INVALID_REVIEW_SORT);
		}
		for (ReviewSort sort : values()) {
			if (sort.value.equals(value.toLowerCase(Locale.ROOT))) {
				return sort;
			}
		}
		throw new ReviewException(ReviewErrorCode.INVALID_REVIEW_SORT);
	}
}
