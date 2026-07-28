package sweetgirl.croissant.member.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Member {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String nickname;

	@Column
	private LocalDateTime deletedAt;

	@Column(nullable = false, columnDefinition = "boolean default true")
	private boolean agreeToTerms;

	@Column(nullable = false, columnDefinition = "boolean default false")
	private boolean agreeToMarketing;

	@Column(nullable = false, columnDefinition = "boolean default false")
	private boolean agreeToNightMarketing;

	@Column(nullable = false, columnDefinition = "boolean default false")
	private boolean agreeToPrivacy;

	@Column(nullable = false, columnDefinition = "boolean default false")
	private boolean agreeToAgeOver14;

	@Column(nullable = false, columnDefinition = "boolean default false")
	private boolean agreeToLocation;

	@Column(length = 20)
	private String termsVersion;

	@Column(length = 20)
	private String privacyVersion;

	@Column
	private LocalDateTime termsAgreedAt;

	@Column
	private LocalDateTime marketingAgreedAt;

	@Column(nullable = false, length = 20, columnDefinition = "VARCHAR DEFAULT 'USER'")
	private String memberType = "USER";

	@CreatedDate
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column
	private LocalDateTime updatedAt;

	@Builder
	public Member(String nickname) {
		this.nickname = nickname;
		this.agreeToTerms = true;
		this.termsAgreedAt = LocalDateTime.now();
	}

	public void updateMarketingAgreement(boolean agreeToMarketing) {
		this.agreeToMarketing = agreeToMarketing;
		this.marketingAgreedAt = agreeToMarketing ? LocalDateTime.now() : null;
	}

	public void updateNightMarketingAgreement(boolean agreeToNightMarketing) {
		this.agreeToNightMarketing = agreeToNightMarketing;
	}

	public void applyInitialConsent(
			boolean termsAgreed, boolean privacyAgreed, boolean ageOver14Agreed,
			boolean marketingAgreed, boolean nightMarketingAgreed, boolean locationAgreed,
			String termsVersion, String privacyVersion) {
		LocalDateTime now = LocalDateTime.now();
		this.agreeToTerms = termsAgreed;
		this.agreeToPrivacy = privacyAgreed;
		this.agreeToAgeOver14 = ageOver14Agreed;
		this.agreeToMarketing = marketingAgreed;
		this.agreeToNightMarketing = nightMarketingAgreed;
		this.agreeToLocation = locationAgreed;
		this.termsAgreedAt = termsAgreed ? now : null;
		this.marketingAgreedAt = marketingAgreed ? now : null;
		this.termsVersion = termsVersion;
		this.privacyVersion = privacyVersion;
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}

	public void resetNickname(String nickname) {
		this.nickname = nickname;
	}

	// 게스트는 약관 동의 절차를 거치지 않는다. 생성자가 기본으로 넣는 동의 표시를
	// 지워 기록이 사실과 어긋나지 않게 한다(동의는 앱에서 받은 뒤 applyInitialConsent로 채운다).
	public void markAsGuest() {
		this.memberType = "GUEST";
		this.agreeToTerms = false;
		this.termsAgreedAt = null;
	}

	// 게스트로 쓰던 계정이 소셜 로그인으로 승격될 때. id를 유지하므로 코스/찜 소유권이 그대로 따라간다.
	public void promoteToUser() {
		this.memberType = "USER";
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}

}
