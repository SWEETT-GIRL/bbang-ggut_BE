package sweetgirl.croissant.member.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.member.entity.Member;

public record ConsentResponse(
		boolean agreeToTerms,
		boolean agreeToMarketing,
		boolean agreeToNightMarketing,
		boolean agreeToLocation,
		LocalDateTime termsAgreedAt,
		LocalDateTime marketingAgreedAt) {

	public static ConsentResponse from(Member member) {
		return new ConsentResponse(
			member.isAgreeToTerms(),
			member.isAgreeToMarketing(),
			member.isAgreeToNightMarketing(),
			member.isAgreeToLocation(),
			member.getTermsAgreedAt(),
			member.getMarketingAgreedAt());
	}
}
