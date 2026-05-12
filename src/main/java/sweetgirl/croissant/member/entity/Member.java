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

	@Column(nullable = false, length = 50)
	private String nickname;

	@Column
	private LocalDateTime deletedAt;

	@Column(nullable = false, columnDefinition = "boolean default true")
	private boolean agreeToTerms;

	@Column(nullable = false, columnDefinition = "boolean default false")
	private boolean agreeToMarketing;

	@Column
	private LocalDateTime termsAgreedAt;

	@Column
	private LocalDateTime marketingAgreedAt;

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

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}
}
