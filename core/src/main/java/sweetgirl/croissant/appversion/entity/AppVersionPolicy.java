package sweetgirl.croissant.appversion.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.appversion.enums.AppPlatform;
import sweetgirl.croissant.common.entity.BaseTimeEntity;

/**
 * 플랫폼별 앱 버전 정책 — 어드민이 숫자만 바꾸면 앱이 스스로 막히거나(min) 안내한다(latest).
 *
 * 판단은 앱이 한다: 서버는 정책값만 내려주고 앱이 자기 버전과 비교한다. 서버가 "너 막힘"을
 * 판정하면 요청마다 버전을 실어야 하고 응답을 캐시할 수 없다.
 * 두 값 모두 null이면 아무 것도 안 한다 — 스토어 출시 전 플랫폼(안드로이드)의 기본 상태다.
 */
@Entity
@Table(name = "app_version_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppVersionPolicy extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, unique = true, length = 20)
	private AppPlatform platform;

	/** 이보다 낮은 버전은 앱을 못 쓴다(하드 블록). null이면 막지 않는다 */
	@Column(length = 20)
	private String minSupportedVersion;

	/** 이보다 낮은 버전에 "새 버전이 있어요"를 띄운다(소프트 넛지). null이면 안내하지 않는다 */
	@Column(length = 20)
	private String latestVersion;

	@Column(length = 512)
	private String storeUrl;

	/** 안내 화면에 덧붙일 문구 — 없으면 앱 기본 문구 */
	@Column(length = 500)
	private String message;

	@Builder
	public AppVersionPolicy(AppPlatform platform, String minSupportedVersion, String latestVersion,
			String storeUrl, String message) {
		this.platform = platform;
		this.minSupportedVersion = minSupportedVersion;
		this.latestVersion = latestVersion;
		this.storeUrl = storeUrl;
		this.message = message;
	}

	public void update(String minSupportedVersion, String latestVersion, String storeUrl, String message) {
		this.minSupportedVersion = minSupportedVersion;
		this.latestVersion = latestVersion;
		this.storeUrl = storeUrl;
		this.message = message;
	}
}
