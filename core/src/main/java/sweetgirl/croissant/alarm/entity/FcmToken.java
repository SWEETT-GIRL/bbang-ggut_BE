package sweetgirl.croissant.alarm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import sweetgirl.croissant.alarm.enums.DeviceOs;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;

@Entity
@Table(name = "fcm_token", uniqueConstraints = @UniqueConstraint(name = "uq_fcm_token_member_device", columnNames = {
	"member_id", "device_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FcmToken extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(nullable = false, length = 200)
	private String deviceId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private DeviceOs deviceOs;

	@Column(nullable = false, length = 4096)
	private String token;

	@Column(length = 100)
	private String appVersion;

	@Column(length = 100)
	private String buildVersion;

	@Column(length = 200)
	private String bundleId;

	@Column(nullable = false)
	private boolean isActive = true;

	@Builder
	public FcmToken(Member member, String deviceId, DeviceOs deviceOs, String token, String appVersion,
			String buildVersion, String bundleId) {
		this.member = member;
		this.deviceId = deviceId;
		this.deviceOs = deviceOs;
		this.token = token;
		this.appVersion = appVersion;
		this.buildVersion = buildVersion;
		this.bundleId = bundleId;
		this.isActive = true;
	}

	public void updateToken(String token) {
		updateToken(token, appVersion, buildVersion, bundleId);
	}

	public void updateToken(String token, String appVersion, String buildVersion, String bundleId) {
		this.token = token;
		this.appVersion = appVersion;
		this.buildVersion = buildVersion;
		this.bundleId = bundleId;
		this.isActive = true;
	}

	public void deactivate() {
		this.isActive = false;
	}
}
