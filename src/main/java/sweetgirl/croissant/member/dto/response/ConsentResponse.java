package sweetgirl.croissant.member.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.member.entity.Member;

public record ConsentResponse(
		boolean agreeToTerms,
		boolean agreeToMarketing,
		LocalDateTime termsAgreedAt,
		LocalDateTime marketingAgreedAt) {

	public static ConsentResponse from(Member member) {
		return new ConsentResponse(
			member.isAgreeToTerms(),
			member.isAgreeToMarketing(),
			member.getTermsAgreedAt(),
			member.getMarketingAgreedAt());
	}
}
