package sweetgirl.croissant.store.entity;

import java.time.Instant;

import org.locationtech.jts.geom.Point;

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
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.store.enums.StoreCategory;
import sweetgirl.croissant.store.enums.StoreSource;
import sweetgirl.croissant.store.enums.StoreStatus;

@Entity
@Table(name = "store")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 255)
	private String address;

	@Column(name = "region_1st", length = 50)
	private String region1st;

	@Column(name = "region_2nd", length = 50)
	private String region2nd;

	@Column(name = "region_3rd", length = 50)
	private String region3rd;

	@Column(nullable = false, columnDefinition = "geography(Point, 4326)")
	private Point location;

	@Column(length = 20)
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private StoreStatus status;

	@Column(name = "kakao_place_id", length = 50, unique = true)
	private String kakaoPlaceId;

	// 네이버 플레이스 매칭 결과 — 최초 1회 검색으로 얻고 영구화. 사진/메뉴 재크롤링 시 검색 단계 스킵.
	@Column(name = "naver_place_id", length = 50, unique = true)
	private String naverPlaceId;

	// 네이버 매칭 시도 시각 — 성공/실패와 무관하게 시도 시점에 기록.
	// naverPlaceId NULL + naverMatchedAt NULL    → 미시도 (큐 대상)
	// naverPlaceId NULL + naverMatchedAt 있음    → 매칭 실패 (재시도 정책 적용)
	// naverPlaceId 있음                          → 매칭 완료
	@Column(name = "naver_matched_at")
	private Instant naverMatchedAt;

	// 네이버 검색 '진짜 무결과(후보 0건)' 누적 실패 횟수 — 폐업/오등록/빵집 아님 등으로 영영 못 찾는
	// 가게를 무한 재시도하지 않도록 파킹하는 카운터. MISMATCH(후보 있음)는 증가시키지 않는다.
	// 임계치 이상이면 enrich 큐에서 제외. retry/clear 시 0 으로 리셋되어 다시 큐 진입.
	@Column(name = "naver_enrich_attempts", nullable = false)
	private int naverEnrichAttempts;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private StoreSource source;

	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private StoreCategory category;

	@Builder
	public Store(String name, String address, String region1st, String region2nd, String region3rd,
			Point location, String phone, String kakaoPlaceId, StoreSource source, StoreCategory category) {
		this.name = name;
		this.address = address;
		this.region1st = region1st;
		this.region2nd = region2nd;
		this.region3rd = region3rd;
		this.location = location;
		this.phone = phone;
		this.kakaoPlaceId = kakaoPlaceId;
		this.source = source != null ? source : StoreSource.ADMIN_REGISTERED;
		this.status = StoreStatus.ACTIVE;
		this.category = category;
	}

	public void markNaverMatched(String naverPlaceId) {
		this.naverPlaceId = naverPlaceId;
		this.naverMatchedAt = Instant.now();
	}

	// 매칭 실패 기록. noCandidates(검색 후보 0건 = 진짜 무결과)면 파킹 카운터를 +1 한다.
	// MISMATCH(후보는 떴으나 게이트 거부)는 수동 후보선택 대상이라 카운트하지 않는다.
	public void markNaverEnrichFailed(boolean noCandidates) {
		this.naverMatchedAt = Instant.now();
		if (noCandidates) {
			this.naverEnrichAttempts++;
		}
	}

	// 잘못 매칭된 ID 초기화 — 큐에 다시 진입. 파킹 카운터도 리셋.
	public void clearNaverMatch() {
		this.naverPlaceId = null;
		this.naverMatchedAt = null;
		this.naverEnrichAttempts = 0;
	}

	// 실패 throttle 강제 해제 — 큐 진입 (naverPlaceId 는 유지하지 않음, 어차피 null 인 상태에서만 의미 있음).
	// 파킹된 가게도 운영자가 명시적으로 재시도하는 것이므로 카운터를 리셋해 큐에 다시 노출.
	public void resetNaverEnrichThrottle() {
		this.naverMatchedAt = null;
		this.naverEnrichAttempts = 0;
	}

	public void update(String name, String address, Point location, String region1st, String region2nd,
			String region3rd, String phone, StoreStatus status, StoreCategory category) {
		if (name != null) {
			this.name = name;
		}
		if (address != null) {
			this.address = address;
		}
		if (location != null) {
			this.location = location;
		}
		if (region1st != null) {
			this.region1st = region1st;
		}
		if (region2nd != null) {
			this.region2nd = region2nd;
		}
		if (region3rd != null) {
			this.region3rd = region3rd;
		}
		if (phone != null) {
			this.phone = phone;
		}
		if (status != null) {
			this.status = status;
		}
		if (category != null) {
			this.category = category;
		}
	}
}
