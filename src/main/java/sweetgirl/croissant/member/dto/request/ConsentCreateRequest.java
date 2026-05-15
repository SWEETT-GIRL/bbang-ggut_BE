package sweetgirl.croissant.member.dto.request;

import jakarta.validation.constraints.NotNull;

public record ConsentCreateRequest(
		@NotNull Boolean termsAgreed,
		@NotNull Boolean privacyAgreed,
		@NotNull Boolean ageOver14Agreed,
		@NotNull Boolean marketingAgreed,
		@NotNull Boolean nightMarketingAgreed,
		String termsVersion,
		String privacyVersion,
		String agreedAt) {
}
