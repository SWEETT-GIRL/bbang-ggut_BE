package sweetgirl.croissant.recommendation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.common.util.GeoDistance;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.exception.RecommendationException;

/**
 * 코스용 상권 = 빵여권 도장 칸의 서버 판 (설계 §1 · §4).
 *
 * 코스 생성 직후에는 위치가 없어서(시/도와 날짜뿐) 서버가 이 표에서 상권 후보를 골라 칩으로 준다.
 * 앵커 추천은 앵커 좌표 + 반경으로 끝나므로 이 표를 쓰지 않는다.
 * 도보·차량 같은 이동 수단 구분은 두지 않는다 — 앱에 지도 기능이 없어 사용자의 이동 수단을 모른다.
 *
 * 소속 판정은 반경이 주 규칙이고, region2nd · region3rd 는 보조다.
 *   region2nd 만 있으면 그 시군구 전체(서울 밖 도장 칸), 둘 다 있으면 그 읍면동, 둘 다 없으면 반경만.
 * search_region 은 HOT_PLACE 행이 있을 때 id 로만 참조한다(store 모듈과 연관을 맺지 않는다).
 */
@Entity
@Table(name = "course_area")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseArea extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** courses.region 과 같은 표기 (예: 서울). 코스에서 상권 목록을 찾는 키다. */
	@Column(nullable = false, length = 20)
	private String sido;

	/**
	 * 빵여권 도장 칸 라벨 (예: 서귀포시). 칩은 같은 (sido, passportSpot) 행들의 묶음이고 칩 이름이 이 값이다 — 관광지 도장 칸
	 * 아래 동네가 여럿이어도 칩은 하나다(#736). display_order 가 가장 작은 행이 대표(칩 id)다.
	 */
	@Column(name = "passport_spot", nullable = false, length = 50)
	private String passportSpot;

	/** 동네 이름 (예: 성수동 · 중문). 결과 제목에 보인다. 동네가 하나뿐인 칸은 도장 칸 라벨과 같다. */
	@Column(nullable = false, length = 50)
	private String name;

	@Column(name = "search_region_id")
	private Long searchRegionId;

	@Column(name = "center_lat", nullable = false)
	private double centerLat;

	@Column(name = "center_lng", nullable = false)
	private double centerLng;

	@Column(name = "radius_m", nullable = false)
	private int radiusM;

	/** 코스 안 인접 두 가게 사이의 상한. 불변 조건(설계 §5.5)이다. */
	@Column(name = "max_hop_m", nullable = false)
	private int maxHopM;

	/** 소속 판정 보조: 시군구 (store.region_2nd 표기). null 이면 반경만 본다. */
	@Column(name = "region_2nd", length = 50)
	private String region2nd;

	/** 소속 판정 보조: 읍면동 (store.region_3rd 에 실제로 있는 표기). region2nd 없이 올 수 없다. */
	@Column(name = "region_3rd", length = 50)
	private String region3rd;

	@Column(name = "is_active", nullable = false)
	private boolean isActive;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	@Builder(access = AccessLevel.PRIVATE)
	private CourseArea(
			String sido,
			String passportSpot,
			String name,
			Long searchRegionId,
			double centerLat,
			double centerLng,
			int radiusM,
			int maxHopM,
			String region2nd,
			String region3rd,
			int displayOrder) {
		this.sido = sido;
		this.passportSpot = passportSpot;
		this.name = name;
		this.searchRegionId = searchRegionId;
		this.centerLat = centerLat;
		this.centerLng = centerLng;
		this.radiusM = radiusM;
		this.maxHopM = maxHopM;
		this.region2nd = region2nd;
		this.region3rd = region3rd;
		this.isActive = true;
		this.displayOrder = displayOrder;
	}

	public static CourseArea create(
			String sido,
			String passportSpot,
			String name,
			Long searchRegionId,
			double centerLat,
			double centerLng,
			int radiusM,
			int maxHopM,
			String region2nd,
			String region3rd,
			int displayOrder) {
		if (radiusM <= 0 || maxHopM <= 0) {
			throw new RecommendationException(RecommendationErrorCode.COURSE_AREA_RADIUS_INVALID);
		}
		if (region3rd != null && region2nd == null) {
			throw new RecommendationException(RecommendationErrorCode.COURSE_AREA_REGION_INVALID);
		}
		return CourseArea.builder()
			.sido(sido)
			.passportSpot(passportSpot)
			.name(name)
			.searchRegionId(searchRegionId)
			.centerLat(centerLat)
			.centerLng(centerLng)
			.radiusM(radiusM)
			.maxHopM(maxHopM)
			.region2nd(region2nd)
			.region3rd(region3rd)
			.displayOrder(displayOrder)
			.build();
	}

	/** 좌표가 이 동네의 반경 안인가 — 코스에 담긴 가게로 동네를 고를 때 쓴다(#736). */
	public boolean covers(double latitude, double longitude) {
		return GeoDistance.metersBetween(centerLat, centerLng, latitude, longitude) <= radiusM;
	}

	public void activate() {
		this.isActive = true;
	}

	public void deactivate() {
		this.isActive = false;
	}

	/** 반경 말고 시군구·동 일치로도 소속을 판정하는 상권인지. */
	public boolean hasRegionRule() {
		return this.region2nd != null;
	}

	/** 시군구 전체를 상권으로 보는지 (서울 밖 도장 칸). */
	public boolean coversWholeDistrict() {
		return this.region2nd != null && this.region3rd == null;
	}
}
