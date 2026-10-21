package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;

class AppleTokenVerifierTest {

	private final AppleTokenVerifier verifier = new AppleTokenVerifier();

	@Test
	void verify_throwsUnauthorizedMemberException_whenTokenIsMalformed() {
		assertThatThrownBy(() -> verifier.verify("invalid.identity.token"))
			.isInstanceOfSatisfying(MemberException.class,
				ex -> org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
					.isEqualTo(MemberErrorCode.SOCIAL_TOKEN_INVALID));
	}
}
