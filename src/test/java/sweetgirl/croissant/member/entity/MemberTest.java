package sweetgirl.croissant.member.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 계정이 생겼다는 것과 약관에 동의했다는 것은 다른 사실이다.
 *
 * 예전에는 생성자가 agreeToTerms=true를 박아서, 동의 절차를 거치지 않은 계정이
 * '동의함'으로 기록됐다. 게스트 경로만 사후에 지우는 식으로 한 번 고쳤다가
 * 소셜 가입 경로가 그대로 남는 재발이 있었으므로, 생성 경로 전체를 여기서 못 박는다.
 */
class MemberTest {

	@Test
	@DisplayName("새로 만든 회원은 약관에 동의하지 않은 상태다 — 생성이 동의를 뜻하지 않는다")
	void newMember_hasNoTermsConsent() {
		Member member = Member.builder().nickname("청순한 포카치아").build();

		assertThat(member.isAgreeToTerms()).isFalse();
		assertThat(member.getTermsAgreedAt()).isNull();
	}

	@Test
	@DisplayName("게스트로 표시해도 동의 여부는 그대로 미동의다")
	void markAsGuest_leavesConsentUnagreed() {
		Member member = Member.builder().nickname("청순한 포카치아").build();

		member.markAsGuest();

		assertThat(member.getMemberType()).isEqualTo("GUEST");
		assertThat(member.isAgreeToTerms()).isFalse();
		assertThat(member.getTermsAgreedAt()).isNull();
	}

	@Test
	@DisplayName("동의는 applyInitialConsent로 받았을 때만 기록된다")
	void applyInitialConsent_recordsAgreement() {
		Member member = Member.builder().nickname("청순한 포카치아").build();

		member.applyInitialConsent(true, true, true, false, false, true, "v1", "v1");

		assertThat(member.isAgreeToTerms()).isTrue();
		assertThat(member.getTermsAgreedAt()).isNotNull();
		// 동의하지 않은 항목까지 덩달아 켜지지 않는다
		assertThat(member.isAgreeToMarketing()).isFalse();
		assertThat(member.getMarketingAgreedAt()).isNull();
	}

	@Test
	@DisplayName("게스트가 회원으로 승격돼도 동의 기록은 사실 그대로 따라간다")
	void promoteToUser_keepsConsentAsRecorded() {
		Member member = Member.builder().nickname("청순한 포카치아").build();
		member.markAsGuest();
		member.applyInitialConsent(true, true, true, false, false, false, "v1", "v1");

		member.promoteToUser(LocalDateTime.of(2026, 10, 12, 9, 0));

		assertThat(member.getMemberType()).isEqualTo("USER");
		assertThat(member.isAgreeToTerms()).isTrue();
	}

	@Test
	@DisplayName("게스트가 승격된 때가 가입 시각이다 — 게스트로 처음 들어온 때가 아니다")
	void promoteToUser_recordsRegisteredAt() {
		Member member = Member.builder().nickname("청순한 포카치아").build();
		member.markAsGuest();
		LocalDateTime promotedAt = LocalDateTime.of(2026, 10, 12, 9, 0);

		member.promoteToUser(promotedAt);

		assertThat(member.getRegisteredAt()).isEqualTo(promotedAt);
		assertThat(member.isRejoined()).isFalse();
	}

	@Test
	@DisplayName("재가입한 회원은 탈퇴한 옛 회원을 가리킨다")
	void rejoining_pointsToPreviousMember() {
		LocalDateTime rejoinedAt = LocalDateTime.of(2026, 10, 12, 9, 0);

		Member member = Member.rejoining("청순한 포카치아", 41L, rejoinedAt);

		assertThat(member.isRejoined()).isTrue();
		assertThat(member.getPreviousMemberId()).isEqualTo(41L);
		assertThat(member.getRegisteredAt()).isEqualTo(rejoinedAt);
	}

	@Test
	@DisplayName("게스트는 가입 시각이 없다")
	void guest_hasNoRegisteredAt() {
		Member member = Member.builder().nickname("청순한 포카치아").build();
		member.markAsGuest();

		assertThat(member.getRegisteredAt()).isNull();
	}
}
