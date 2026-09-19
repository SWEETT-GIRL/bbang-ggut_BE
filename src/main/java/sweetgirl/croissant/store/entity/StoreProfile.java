package sweetgirl.croissant.store.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.store.enums.AxisSource;

/**
 * 가게 취향 프로필 — dataops 배치 산출물 (설계 §4 · §5.1).
 *
 * 누가 보든 같은 가게 사실이라 store 에 둔다. 축은 사용자와 같은 공간의 값이라 personalization 의 {@link TasteAxis} 를 임베드한다.
 *
 * 쓰는 쪽은 dataops 배치 하나뿐이다 — dataops_writer 롤로 직접 upsert 하고, ServingWriter(store · menu 같은 BE 소유 표에
 * apply_log 를 남기며 쓰는 통로)는 거치지 않는다. 사람이 고치는 값(store)과 배치가 통째로 덮어쓰는 값(이 표)을 가르는 것이
 * 이 표를 store 에 두지 않는 이유다. BE 는 읽기만 한다. {@code @Immutable} 이라 BE 가 실수로 저장해도
 * UPDATE 가 나가지 않는다 — 한 행을 두 주체가 쓰면 증분 upsert 와 JPA 저장이 서로 덮어쓴다.
 * 인기 prior 는 여기 없다. BE 소유 store.popularity_score 와 이 표의 reviewCount·ratingCount·reviewScore 를 후보 SQL 이 요청 때 합친다.
 * store 와 연관을 맺지 않고 store_id 만 갖는다. 후보 조회는 네이티브 SQL 이 store 와 조인한다.
 * 이 테이블이 비어 있어도 추천은 돌아가야 한다: 축이 없으면 취향 항을 빼고 인기 순으로 내려간다.
 */
@Entity
@Immutable
@Table(name = "store_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreProfile extends BaseTimeEntity {

	@Id
	@Column(name = "store_id")
	private Long storeId;

	/** 네 컬럼이 전부 NULL 이면 null. "근거 없음"이다. */
	@Embedded
	private TasteAxis axis;

	@Enumerated(EnumType.STRING)
	@Column(name = "axis_source", length = 10)
	private AxisSource axisSource;

	/**
	 * 이 가게가 파는 <b>빵</b>의 성격 — 4글자 코드(예: {@code PCLN}). 가게의 성격이 아니다 — 네 축이 전부 메뉴 구성에서 나온다.
	 * dataops 가 축과 같은 배치에서 함께 쓰므로 축과 어긋나지 않는다. 규칙은 {@link TasteAxis#typeCode()}.
	 */
	@Column(name = "bread_type", length = 4)
	private String breadType;

	/**
	 * 취향 태그 8 (0~1). 키는 {@link sweetgirl.croissant.personalization.enums.TasteTag} 의 key 그대로이고 dataops 는 모든 가게에
	 * 8키를 다 넣는다. 투표 태그는 0.5 가 보통 빵집이다. 예: {"머물기": 0.82, "가성비": 0.5}
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, Double> tags;

	/**
	 * 편의시설 10종과 newOpening. 필터·표시용이라 점수에 넣지 않는다. 예: {"parking": true, "takeout": true, "newOpening": true}.
	 * 리뷰 수·별점은 아래 컬럼으로 빠졌고 category 는 store.category 로 나갔다(#730).
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, Object> attrs;

	/** 메뉴 ∪ 리뷰 언급 종류. 사용자 top_kinds 와의 겹침 계산에 쓴다. */
	@JdbcTypeCode(SqlTypes.ARRAY)
	private String[] kinds;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "signature_menus")
	private String[] signatureMenus;

	/**
	 * 대표 메뉴를 빵 종류 이름(menu_category)으로 푼 것 — 대표 메뉴 순서, 일반 종류 없음. 메뉴 이름과 달리 회원 top_kinds 와
	 * 바로 맞춰 볼 수 있다. 풀린 게 없으면 빈 배열이다(NULL 아님). 아직 읽는 곳은 없다(#662).
	 */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "signature_kinds", nullable = false)
	private String[] signatureKinds;

	/** 빵집다움 0~1. 후보 필터는 0.5 이상만 (설계 §5.3). */
	@Column(name = "bakery_score", nullable = false, precision = 4, scale = 3)
	private BigDecimal bakeryScore;

	/** 계산 시점의 store.status 스냅샷. 후보 SQL 은 store.status 를 본다. */
	@Column(name = "store_status", length = 10)
	private String storeStatus;

	/** 축·태그 산식 버전. 산식이 바뀌면 이 값으로 재계산 대상을 고른다. */
	@Column(name = "batch_version", nullable = false, length = 20)
	private String batchVersion;

	/** 방문자 리뷰 수(`visitorReviewsTotal`). 인기 prior 의 동순위를 가르는 값이다. */
	@Column(name = "review_count")
	private Integer reviewCount;

	/**
	 * 별점을 매긴 수(`ratingReviewsTotal`) — 별점 신뢰도의 분모다. {@link #reviewCount} 와 다르다(중앙값 26%).
	 * 출처는 VisitorReviewStatsResult.ratingReviewsTotal(analysis 의 부모 노드, dataops 파서 v2). 네이버가 안 준 가게는 null.
	 */
	@Column(name = "rating_count")
	private Integer ratingCount;

	/**
	 * 평균 별점 1.00~5.00, 소수 두 자리. 0 과 1 미만은 배치가 결측으로 바꿔 넣는다 —
	 * 0 은 0점이 아니라 네이버가 표본이 적어 평균을 안 주는 상태다.
	 * 점수 계산에서는 리뷰 10건 미만을 전체 평균 쪽으로 당긴다.
	 */
	@Column(name = "review_score", precision = 3, scale = 2)
	private BigDecimal reviewScore;

	/**
	 * 네이버 소개글 원문. 점주가 쓴 마케팅 문구라 검증된 사실이 아니다 —
	 * 프롬프트에 넣을 때 설계 §7.1 규칙 2("제공된 정보 밖의 사실은 말하지 않는다")를 어떻게 지킬지는 #662 에서 정한다.
	 */
	@Column(name = "description", columnDefinition = "text")
	private String description;

	@Column(name = "computed_at", nullable = false)
	private LocalDateTime computedAt;

	@Builder
	private StoreProfile(
			Long storeId,
			TasteAxis axis,
			AxisSource axisSource,
			String breadType,
			Map<String, Double> tags,
			Map<String, Object> attrs,
			String[] kinds,
			String[] signatureMenus,
			String[] signatureKinds,
			BigDecimal bakeryScore,
			Integer reviewCount,
			Integer ratingCount,
			BigDecimal reviewScore,
			String description,
			String storeStatus,
			String batchVersion,
			LocalDateTime computedAt) {
		this.storeId = storeId;
		this.axis = axis;
		this.axisSource = axisSource;
		this.breadType = breadType;
		this.tags = tags != null ? new HashMap<>(tags) : new HashMap<>();
		this.attrs = attrs != null ? new HashMap<>(attrs) : new HashMap<>();
		this.kinds = kinds;
		this.signatureMenus = signatureMenus;
		this.signatureKinds = signatureKinds != null ? signatureKinds : new String[0];
		this.bakeryScore = bakeryScore;
		this.reviewCount = reviewCount;
		this.ratingCount = ratingCount;
		this.reviewScore = reviewScore;
		this.description = description;
		this.storeStatus = storeStatus;
		this.batchVersion = batchVersion;
		this.computedAt = computedAt;
	}

	/** 운영에서는 dataops 가 INSERT 한다. 빌더는 테스트·랩 픽스처용이다 — 인자가 많아 이름 있는 쪽이 안전하다. */
	public boolean hasAxis() {
		return axis != null && axis.isComplete();
	}
}
