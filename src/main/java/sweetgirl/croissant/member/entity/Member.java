package sweetgirl.croissant.member.entity;

import java.time.LocalDate;
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

	// 닉네임 뽑기로 받은 후보. 수락해야 nickname 이 되고, "원래대로"를 누르면 비운다.
	@Column(length = 100)
	private String pendingNickname;

	// 마지막으로 닉네임을 뽑은 시각(Asia/Seoul 벽시계). 뽑기는 한국 날짜로 하루 한 번이다.
	@Column
	private LocalDateTime nicknameDrawnAt;

	@Column
	private LocalDateTime deletedAt;

	@Column(nullable = false, columnDefinition = "boolean default false")
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

	// 계정이 생겼다는 것과 약관에 동의했다는 것은 다른 사실이다. 생성 시점에 동의를 박으면
	// 동의한 적 없는 사람이 '동의함'으로 남는다 — 동의는 applyInitialConsent가 받은 뒤에만 채운다.
	@Builder
	public Member(String nickname) {
		this.nickname = nickname;
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

	public boolean canDrawNickname(LocalDate today) {
		return this.nicknameDrawnAt == null || this.nicknameDrawnAt.toLocalDate().isBefore(today);
	}

	// 뽑는 순간 그날 기회를 쓴 것이다 — 수락하든 "원래대로"를 누르든 다시 뽑을 수 없다.
	public void drawNickname(String candidate, LocalDateTime now) {
		this.pendingNickname = candidate;
		this.nicknameDrawnAt = now;
	}

	public void acceptPendingNickname() {
		this.nickname = this.pendingNickname;
		this.pendingNickname = null;
	}

	public void discardPendingNickname() {
		this.pendingNickname = null;
	}

	public void markAsGuest() {
		this.memberType = "GUEST";
	}

	// 게스트로 쓰던 계정이 소셜 로그인으로 승격될 때. id를 유지하므로 코스/찜 소유권이 그대로 따라간다.
	public void promoteToUser() {
		this.memberType = "USER";
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}

	public boolean isGuest() {
		return "GUEST".equals(this.memberType);
	}

}
