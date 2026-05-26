package sweetgirl.croissant.member.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.global.encrypt.EncryptedStringConverter;
import sweetgirl.croissant.member.enums.OAuthProvider;

@Entity
@Table(name = "oauth", uniqueConstraints = @UniqueConstraint(name = "uq_oauth", columnNames = {
	"provider", "providerId"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class OAuth {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OAuthProvider provider;

	// providerId는 카카오/애플 고유 식별자 — SHA-256 해시값(64자)으로 저장
	@Column(nullable = false, length = 255)
	private String providerId;

	// 이메일은 PII이므로 AES-256-GCM 암호화 후 저장
	@Convert(converter = EncryptedStringConverter.class)
	@Column(length = 500)
	private String email;

	@CreatedDate
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder
	public OAuth(Member member, OAuthProvider provider, String providerId, String email) {
		this.member = member;
		this.provider = provider;
		this.providerId = providerId;
		this.email = email;
	}

	public void updateMember(Member newMember) {
		this.member = newMember;
	}
}
