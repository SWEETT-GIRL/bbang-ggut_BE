package sweetgirl.croissant.recommendation.entity;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
import sweetgirl.croissant.recommendation.enums.RecommendationMode;

/**
 * 추천 요청 1건 = 행 1개 (설계 §4 · §5.5). 서빙에 필요한 것만 갖는다.
 *
 * result 는 사용자에게 내려간 최종본이고, 재사용(같은 코스·모드·상권/앵커·일차·프로필 버전)과 피드백 해석의 기준이다.
 * 여정 변수는 Amplitude, 지연·토큰은 Micrometer, 후보 목록과 LLM 원문은 recommendation_id 를 단 구조화 로그에 남긴다
 * (플래닝 문서 §12). 만들어진 뒤에는 바꾸지 않는다. member · course 와 연관을 맺지 않고 id 만 갖는다.
 */
@Entity
@Table(name = "recommendation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recommendation {

	public static final String RULE_PROVIDER = "rule";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "course_id", nullable = false)
	private Long courseId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private RecommendationMode mode;

	@Column(name = "course_area_id")
	private Long courseAreaId;

	@Column(name = "anchor_store_id")
	private Long anchorStoreId;

	/** 요청의 day. 재사용 키의 일부다. */
	private Short day;

	/** 계산에 쓴 user_taste_profile.version. 프로필이 바뀌면 옛 추천은 재사용하지 않는다. */
	@Column(name = "profile_version", nullable = false)
	private int profileVersion;

	/** rule | gemini | openai | anthropic. 값 체계는 #662 의 설정이 정한다. */
	@Column(nullable = false, length = 20)
	private String provider;

	@Column(length = 60)
	private String model;

	/** 설계 §7.1 프롬프트 규칙 버전. 룰만 돌았으면 null. */
	@Column(name = "prompt_version", length = 20)
	private String promptVersion;

	/** LLM 실패·타임아웃으로 룰 결과를 내려준 것. 에러가 아니라 결과다. */
	@Column(nullable = false)
	private boolean fallback;

	/** 사용자에게 내려간 최종본: {title, stores: [{storeId, order, reason, tasteFit, distanceToNextM}], totalDistanceM}. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, Object> result;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder(access = AccessLevel.PRIVATE)
	private Recommendation(
			Long memberId,
			Long courseId,
			RecommendationMode mode,
			Long courseAreaId,
			Long anchorStoreId,
			Short day,
			int profileVersion,
			String provider,
			String model,
			String promptVersion,
			boolean fallback,
			Map<String, Object> result) {
		this.memberId = memberId;
		this.courseId = courseId;
		this.mode = mode;
		this.courseAreaId = courseAreaId;
		this.anchorStoreId = anchorStoreId;
		this.day = day;
		this.profileVersion = profileVersion;
		this.provider = provider;
		this.model = model;
		this.promptVersion = promptVersion;
		this.fallback = fallback;
		// LLM 출력엔 null 값이 올 수 있어 Map.copyOf 를 쓰지 않는다.
		this.result = new LinkedHashMap<>(result);
		this.createdAt = LocalDateTime.now();
	}

	/** 상권 추천 (진입점 1). LLM 을 부를 수 있어 provider · model · promptVersion · fallback 을 받는다. */
	public static Recommendation forArea(
			Long memberId,
			Long courseId,
			Long courseAreaId,
			Short day,
			int profileVersion,
			String provider,
			String model,
			String promptVersion,
			boolean fallback,
			Map<String, Object> result) {
		return Recommendation.builder()
			.memberId(memberId)
			.courseId(courseId)
			.mode(RecommendationMode.AREA)
			.courseAreaId(courseAreaId)
			.day(day)
			.profileVersion(profileVersion)
			.provider(provider)
			.model(model)
			.promptVersion(promptVersion)
			.fallback(fallback)
			.result(result)
			.build();
	}

	/** 앵커 추천 (진입점 2). 룰만이라 provider 는 rule, model · promptVersion 은 없고 fallback 도 없다. */
	public static Recommendation forAnchor(
			Long memberId,
			Long courseId,
			Long anchorStoreId,
			Short day,
			int profileVersion,
			Map<String, Object> result) {
		return Recommendation.builder()
			.memberId(memberId)
			.courseId(courseId)
			.mode(RecommendationMode.ANCHOR)
			.anchorStoreId(anchorStoreId)
			.day(day)
			.profileVersion(profileVersion)
			.provider(RULE_PROVIDER)
			.fallback(false)
			.result(result)
			.build();
	}

	public boolean isAnchor() {
		return this.mode == RecommendationMode.ANCHOR;
	}
}
