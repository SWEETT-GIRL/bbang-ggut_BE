package sweetgirl.dataops.region.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 법정동 해소 근거 (v3 P4-4 · FR-11) — scripts/region_resolver.py 가 쓰고, 서버는 읽기만 한다.
 *
 * 한 번의 좌표 역산이 한 행이다(append-only). 조회 좌표·기준표 버전·투영 버전이 같은 행만 같은 근거다 —
 * 가게 ID 나 과거 RESOLVED 만으로 재사용하지 않는다. 원 응답(raw_response)·계층(hierarchy)은 감사용이라 매핑하지 않는다.
 */
@Entity
@Immutable
@Table(name = "region_resolution")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegionResolution {

	@Id
	private Long id;

	@Column(name = "subject_type", nullable = false, length = 20)
	private String subjectType;

	@Column(name = "subject_id", nullable = false, length = 60)
	private String subjectId;

	@Column(nullable = false, length = 40)
	private String purpose;

	@Column(name = "query_lon", nullable = false)
	private Double queryLon;

	@Column(name = "query_lat", nullable = false)
	private Double queryLat;

	@Column(name = "location_fingerprint", nullable = false, length = 40)
	private String locationFingerprint;

	@Column(name = "region_type", nullable = false, length = 10)
	private String regionType;

	@Column(nullable = false, length = 20)
	private String status;

	@Column(length = 40)
	private String reason;

	@Column(name = "bjd_code", length = 10)
	private String bjdCode;

	@Column(name = "reference_version", nullable = false, length = 40)
	private String referenceVersion;

	@Column(name = "projection_version", nullable = false, length = 40)
	private String projectionVersion;

	@Column(name = "region_1st", length = 50)
	private String region1st;

	@Column(name = "region_2nd", length = 50)
	private String region2nd;

	@Column(name = "region_3rd", length = 50)
	private String region3rd;

	@Column(name = "observed_at", nullable = false)
	private LocalDateTime observedAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public boolean isResolved() {
		return "RESOLVED".equals(status) && "B".equals(regionType) && bjdCode != null;
	}

	/** 테스트·조립용 — 운영 행은 resolver 스크립트만 만든다 */
	public static RegionResolution of(Long id, long storeId, double lon, double lat, String status, String reason,
			String bjdCode, String referenceVersion, String projectionVersion,
			String region1st, String region2nd, String region3rd) {
		return of(id, "STORE", String.valueOf(storeId), "ADDRESS_PROPOSAL", lon, lat, status, reason, bjdCode,
			referenceVersion, projectionVersion, region1st, region2nd, region3rd);
	}

	/** 테스트·조립용 — 대상 종류(STORE·CANDIDATE)와 목적을 지정한다 */
	public static RegionResolution of(Long id, String subjectType, String subjectId, String purpose, double lon,
			double lat, String status, String reason, String bjdCode, String referenceVersion,
			String projectionVersion, String region1st, String region2nd, String region3rd) {
		RegionResolution r = new RegionResolution();
		r.id = id;
		r.subjectType = subjectType;
		r.subjectId = subjectId;
		r.purpose = purpose;
		r.queryLon = lon;
		r.queryLat = lat;
		r.locationFingerprint = String.format("%.6f,%.6f", lon, lat);
		r.regionType = bjdCode != null ? "B" : "UNKNOWN";
		r.status = status;
		r.reason = reason;
		r.bjdCode = bjdCode;
		r.referenceVersion = referenceVersion;
		r.projectionVersion = projectionVersion;
		r.region1st = region1st;
		r.region2nd = region2nd;
		r.region3rd = region3rd;
		r.observedAt = LocalDateTime.now();
		r.createdAt = LocalDateTime.now();
		return r;
	}
}
