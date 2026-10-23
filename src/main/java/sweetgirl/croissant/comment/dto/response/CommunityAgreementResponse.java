package sweetgirl.croissant.comment.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.member.entity.Member;

/** {@code agreed} 는 지금 버전에 동의했는가. 옛 버전에 했으면 false 이고 {@code agreedVersion} 에 그 버전이 남는다. */
public record CommunityAgreementResponse(
		boolean agreed,
		LocalDateTime agreedAt,
		String agreedVersion,
		String currentVersion) {

	public static CommunityAgreementResponse from(Member member, String currentVersion) {
		return new CommunityAgreementResponse(
			member.hasAgreedToCommunityRules(currentVersion),
			member.getCommunityAgreedAt(),
			member.getCommunityTermsVersion(),
			currentVersion);
	}
}
