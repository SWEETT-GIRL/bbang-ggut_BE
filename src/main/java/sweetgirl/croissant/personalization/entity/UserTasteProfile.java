package sweetgirl.croissant.personalization.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.personalization.enums.TasteTypeSource;
import sweetgirl.croissant.personalization.enums.TasteUserType;

/**
 * 사용자 취향 프로필 — 한 사람당 한 행 (설계 §4 · §5.1).
 *
 * 축 = 신호 가중 평균 + 검사 응답 × 3. 검사 응답 시 그 사용자만 즉시 재계산하고, 나머지는 배치(#663)가 한다.
 * version 은 재계산마다 올라가며 recommendation.profile_version 과 짝을 이룬다 — 같은 버전이면 저장된 추천을 재사용한다.
 * quizAnsweredAt 은 마지막 검사 시각으로, 타입 카드의 "저장 후 기록 N건"의 기준이다.
 * 게스트 → 로그인 병합 때는 PK 충돌이라 게스트 행을 버리고 재계산한다.
 */
@Entity
@Table(name = "user_taste_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserTasteProfile extends BaseTimeEntity {

	@Id
	@Column(name = "member_id")
	private Long memberId;

	/** 네 컬럼이 전부 NULL 이면 null. 신호도 검사도 없는 사용자다. */
	@Embedded
	private TasteAxis axis;

	/**
	 * 이 사용자의 빵 취향 타입 — 4글자 코드. {@code store_profile.bread_type} 과 같은 체계라 그대로 비교한다.
	 * 규칙은 {@link TasteAxis#typeCode()} 한 곳에 있다. 화면에 보이는 유형 이름은 없다(#760).
	 */
	@Column(name = "type_code", length = 4)
	private String typeCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "type_source", length = 10)
	private TasteTypeSource typeSource;

	/**
	 * 취향 라벨 아홉 값(0~1) — 가게 store_recommendation_feature 와 같은 이름·방향으로 맞댄다.
	 * 아홉이 함께 차거나 함께 비고, 전부 비면 Hibernate 가 null 로 돌려준다(= 근거 없음).
	 * axis 와 부호가 반대인 자리가 있다({@link TasteLabelVector} 주석) — 축 값을 여기에 옮기지 않는다.
	 */
	@Embedded
	private TasteLabelVector labels;

	/** [{"axis": "taste", "choice": "SALTY"}, …] 4문항 또는 8문항. 다시 풀면 덮어쓴다. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "quiz_answers")
	private List<Map<String, String>> quizAnswers;

	/** 마지막으로 검사를 푼 시각. 검사한 적 없으면 null. */
	@Column(name = "quiz_answered_at")
	private LocalDateTime quizAnsweredAt;

	/**
	 * 푼 검사의 판 — 1 은 축마다 두 갈래(A/B), 2 는 갈래 이름 11개. 앱이 보낸 값을 그대로 적고, 안 보내면 null 이다.
	 * 저장된 선택지 이름으로도 지금은 판이 드러나지만, 판이 하나 더 생기면 값만으로 구분되지 않는다.
	 */
	@Column(name = "quiz_version")
	private Short quizVersion;

	/** 가게 태그 상속 합 (0~1). 키는 store_profile 과 같은 한글 태그 이름. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, Double> tags;

	/** 기록·검사에서 드러난 메뉴 종류 상위 3~5. 가게 kinds 와의 겹침 항에 쓴다. */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "top_kinds")
	private String[] topKinds;

	/**
	 * 좋아한 가게들의 대표 종류(store_profile.signature_kinds)로 추정한 종류, lift 상위 3 (#762). 기록 종류(topKinds)가 없는
	 * 회원에게도 "{종류} 취향" 배지를 붙이는 재료다. 기록 종류와 따로 둔다 — 기록이 더 강한 근거다. 없으면 빈 배열.
	 */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "inferred_kinds", nullable = false)
	private String[] inferredKinds;

	@Enumerated(EnumType.STRING)
	@Column(name = "user_type", length = 12)
	private TasteUserType userType;

	/** 저장·계획·담기·기록 가게 수. 3곳 미만이면 카드가 4문항 검사 상태(QUIZ)로 바뀐다. */
	@Column(name = "strong_store_count", nullable = false)
	private int strongStoreCount;

	/** 빵 단위 신호(기록의 빵) 수. 0 이면 "4문항으로 더 정확하게" 링크를 보여준다. */
	@Column(name = "bread_signal_count", nullable = false)
	private int breadSignalCount;

	@Column(nullable = false)
	private int version;

	@Column(name = "computed_at", nullable = false)
	private LocalDateTime computedAt;

	private UserTasteProfile(Long memberId, LocalDateTime computedAt) {
		this.memberId = memberId;
		this.tags = new HashMap<>();
		this.inferredKinds = new String[0];
		this.version = 1;
		this.computedAt = computedAt;
	}

	/** 아직 신호가 없는 사용자의 빈 프로필. 축·타입이 없고 version 1 이다. */
	public static UserTasteProfile createEmpty(Long memberId, LocalDateTime computedAt) {
		return new UserTasteProfile(memberId, computedAt);
	}

	/** 배치 재계산. 신호에서 나온 값으로 전부 갈아끼우고 version 을 올린다. 검사 응답과 그 시각은 건드리지 않는다. */
	public void recompute(
			TasteAxis axis,
			String typeCode,
			TasteTypeSource typeSource,
			Map<String, Double> tags,
			String[] topKinds,
			String[] inferredKinds,
			TasteUserType userType,
			int strongStoreCount,
			int breadSignalCount,
			LocalDateTime computedAt) {
		this.axis = axis;
		this.typeCode = typeCode;
		this.typeSource = typeSource;
		this.tags = tags != null ? new HashMap<>(tags) : new HashMap<>();
		this.topKinds = topKinds;
		this.inferredKinds = inferredKinds != null ? inferredKinds : new String[0];
		this.userType = userType;
		this.strongStoreCount = strongStoreCount;
		this.breadSignalCount = breadSignalCount;
		this.computedAt = computedAt;
		this.version++;
	}

	/**
	 * 유형·카운트만 갱신. 추천이 읽는 값(축·타입·태그·종류)이 아니라 version 은 그대로 둔다 — 저장된 추천의 재사용 키를 깨지 않는다.
	 */
	public void refreshStats(TasteUserType userType, int strongStoreCount, int breadSignalCount,
			LocalDateTime computedAt) {
		this.userType = userType;
		this.strongStoreCount = strongStoreCount;
		this.breadSignalCount = breadSignalCount;
		this.computedAt = computedAt;
	}

	/** 검사 응답과 시각만 남긴다 — 축·타입·version 은 건드리지 않는다. 합산(recompute)이 뒤따르고, 값이 바뀌면 그때 version 이 오른다. */
	public void recordQuizAnswers(List<Map<String, String>> quizAnswers, Short quizVersion,
			LocalDateTime answeredAt) {
		this.quizAnswers = quizAnswers != null ? new ArrayList<>(quizAnswers) : null;
		this.quizVersion = quizVersion;
		this.quizAnsweredAt = answeredAt;
	}

	/** 검사 응답을 저장하고 축·타입을 검사 결과로 바꾼다. 합산이 실패했을 때의 폴백 — 검사가 타입을 결정한다. */
	public void applyQuiz(
			List<Map<String, String>> quizAnswers,
			Short quizVersion,
			TasteAxis axis,
			String typeCode,
			LocalDateTime answeredAt) {
		this.quizAnswers = quizAnswers != null ? new ArrayList<>(quizAnswers) : null;
		this.quizVersion = quizVersion;
		this.quizAnsweredAt = answeredAt;
		this.axis = axis;
		this.typeCode = typeCode;
		this.typeSource = TasteTypeSource.QUIZ;
		this.computedAt = answeredAt;
		this.version++;
	}

	public boolean hasAxis() {
		return axis != null && axis.isComplete();
	}

	public boolean hasType() {
		return typeCode != null;
	}

	public boolean hasTakenQuiz() {
		return quizAnsweredAt != null;
	}
}
