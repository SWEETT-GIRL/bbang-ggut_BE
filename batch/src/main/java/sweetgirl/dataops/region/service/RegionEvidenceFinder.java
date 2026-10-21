package sweetgirl.dataops.region.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import sweetgirl.dataops.region.dto.LocationEvidence;
import sweetgirl.dataops.region.entity.RegionResolution;
import sweetgirl.dataops.region.repository.RegionResolutionRepository;

/**
 * 좌표의 법정동 근거 조회 (v3 P4-4) — 위치 제안 게이트와 신규 등록이 같은 규칙으로 근거를 찾는다.
 *
 * 근거는 대상(가게 ID·후보 관리번호)과 <b>조회 좌표</b>, 지금 기준표·투영 버전이 모두 같은 행이다.
 * 대상 ID 나 과거 RESOLVED 만으로 재사용하지 않는다 — 좌표가 달라지면 다른 근거다.
 * 최신 행이 UNRESOLVED 면 그보다 오래된 RESOLVED 가 있어도 쓰지 않는다.
 */
@Component
public class RegionEvidenceFinder {

	/** region_resolution.subject_type */
	public static final String STORE = "STORE";
	public static final String CANDIDATE = "CANDIDATE";

	private final RegionResolutionRepository regionResolutionRepository;
	private final String referenceVersion;
	private final String projectionVersion;

	public RegionEvidenceFinder(RegionResolutionRepository regionResolutionRepository,
			@Value("${dataops.region.reference-version}") String referenceVersion,
			@Value("${dataops.region.projection-version}") String projectionVersion) {
		this.regionResolutionRepository = regionResolutionRepository;
		this.referenceVersion = referenceVersion;
		this.projectionVersion = projectionVersion;
	}

	/** 조회 결과 — 근거가 있으면 evidence, 없거나 해소되지 않았으면 missingReason */
	public record Lookup(LocationEvidence evidence, String missingReason) {

		public boolean found() {
			return evidence != null;
		}
	}

	/** @param where 보류 사유에 쓸 좌표의 이름 — "새 좌표", "등록 좌표" 등 */
	public Lookup lookup(String subjectType, String subjectId, double lon, double lat, String where) {
		List<RegionResolution> rows = regionResolutionRepository.findEvidence(subjectType, subjectId, lon, lat,
			referenceVersion, projectionVersion, PageRequest.of(0, 1));
		if (rows.isEmpty()) {
			return new Lookup(null, String.format("%s(%.6f, %.6f)의 법정동 해소 근거가 없습니다 — region_resolver 를 먼저 "
				+ "돌려야 합니다 (기준표 %s)", where, lon, lat, referenceVersion));
		}
		RegionResolution latest = rows.get(0);
		if (!latest.isResolved()) {
			return new Lookup(null, String.format("%s의 법정동이 해소되지 않았습니다 — %s (근거 #%d)", where,
				latest.getReason(), latest.getId()));
		}
		return new Lookup(new LocationEvidence(latest.getId(), latest.getBjdCode(), latest.getRegion1st(),
			latest.getRegion2nd(), latest.getRegion3rd(), latest.getReferenceVersion()), null);
	}
}
