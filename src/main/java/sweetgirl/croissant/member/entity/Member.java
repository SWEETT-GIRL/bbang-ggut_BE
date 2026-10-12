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

	// 마지막으로 닉네임을 뽑은 시각(Asia/Seoul 벽시계). 뽑기는 한국 날짜로 하루 한 번이고, 주(월~일) 첫 뽑기만 무료다.
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

	/**
	 * 소셜로 가입한 때. 게스트는 비어 있다.
	 *
	 * <p>{@code createdAt} 과 다르다 — 게스트로 쓰다 로그인하면 같은 행이 승격돼 {@code createdAt} 은
	 * 게스트 발급 시각으로 남는다. 리워드의 "2.0 이전 가입자인가"가 이 값을 본다.
	 */
	@Column
	private LocalDateTime registeredAt;

	/** 탈퇴한 뒤 같은 소셜 계정으로 다시 가입했다면, 탈퇴한 옛 회원의 id. */
	@Column
	private Long previousMemberId;

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

	/** 소셜 계정으로 처음 가입하는 회원. */
	public static Member registering(String nickname, LocalDateTime registeredAt) {
		Member member = new Member(nickname);
		member.registeredAt = registeredAt;
		return member;
	}

	/** 탈퇴한 회원이 같은 소셜 계정으로 다시 가입한다. 옛 회원을 가리켜 둔다 — 환영 선물·초대 코드를 다시 받지 못하게 한다. */
	public static Member rejoining(String nickname, Long previousMemberId, LocalDateTime registeredAt) {
		Member member = registering(nickname, registeredAt);
		member.previousMemberId = previousMemberId;
		return member;
	}

	public boolean isRejoined() {
		return this.previousMemberId != null;
	}

	public void updateMarketingAgreement(boolean agreeToMarketing) {
		this.agreeToMarketing = agreeToMarketing;
		this.marketingAgreedAt = agreeToMarketing ? LocalDateTime.now() : null;
		// 야간 광고 동의는 광고 수신 동의 위에 얹는 동의다 — 광고를 철회하면 야간 동의도 함께 거둔다
		if (!agreeToMarketing) {
			this.agreeToNightMarketing = false;
		}
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

	// 주의 첫 뽑기는 늘 무료다(광고 뽑기는 무료를 쓴 뒤에만 열린다). 그래서 이번 주에 뽑은 적이 있으면 무료는 쓴 것이다.
	public boolean usedFreeNicknameDraw(LocalDate weekStart) {
		return this.nicknameDrawnAt != null && !this.nicknameDrawnAt.toLocalDate().isBefore(weekStart);
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
	public void promoteToUser(LocalDateTime registeredAt) {
		this.memberType = "USER";
		this.registeredAt = registeredAt;
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}

	public boolean isGuest() {
		return "GUEST".equals(this.memberType);
	}

}
