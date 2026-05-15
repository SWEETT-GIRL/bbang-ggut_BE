package sweetgirl.croissant.policy.dto.response;

import sweetgirl.croissant.policy.enums.TermsType;

public record TermsResponse(
		TermsType type,
		String title,
		boolean isRequired,
		String content) {

	public static TermsResponse from(TermsType termsType) {
		return new TermsResponse(
			termsType,
			termsType.getTitle(),
			termsType.isRequired(),
			termsType.getContent());
	}
}
