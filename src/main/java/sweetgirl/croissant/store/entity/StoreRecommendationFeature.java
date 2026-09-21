package sweetgirl.croissant.store.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.personalization.entity.TasteLabelVector;

/**
 * 가게의 취향 라벨 아홉 값 — 회원이 검사에서 고른 갈래와 맞대는 쪽. 적재는 dataops 가 하고 BE 는 읽기만 한다.
 *
 * <p><b>추천 경로는 이 엔티티로 읽지 않는다.</b> 후보 SQL 이 {@code store} 를 진입점으로 삼아
 * {@code LEFT JOIN store_recommendation_feature} 로 한 번에 가져온다 — 가게마다 따로 읽으면 N+1 이고,
 * 무엇보다 <b>라벨 표를 쿼리의 진입점으로 쓰면 {@code status='ACTIVE'} 필터를 빠뜨릴 수 있다.</b>
 * 숨긴 가게가 그대로 추천에 섞이는데 그건 예외도 안 나고 코스는 그럴듯하게 나온다.
 *
 * <p>이 엔티티가 있는 이유는 둘이다 — 중심화 상수(아홉 열의 평균)를 읽는 것, 그리고 테스트 스키마에 이 표가
 * 생기게 하는 것이다. 테스트는 {@code ddl-auto=create-drop} 이라 엔티티가 없으면 표도 없고, 그러면 후보 SQL 의
 * 조인이 테스트에서 아예 돌지 않는다.
 *
 * <p>{@code store_profile.axis_*} 와 <b>같은 가게의 다른 표현</b>이다. 축은 두 극 사이의 위치(−1~+1)라
 * 갈래를 둘까지만 담고, 라벨은 각 성질의 세기(0~1)라 넷이 따로 선다. 부호도 다르다 —
 * {@code axis_filling} 은 꽉 참이 −1 인데 {@code fillingRich} 는 꽉 참이 1 이다. 한쪽을 다른 쪽으로 옮기지 않는다.
 */
@Entity
@Getter
@Table(name = "store_recommendation_feature")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreRecommendationFeature {

	@Id
	@Column(name = "store_id")
	private Long storeId;

	@Embedded
	private TasteLabelVector labels;

	/** MENU · REVIEW · BOTH — 리뷰만으로 만든 값을 메뉴에서 확인된 사실과 같은 강도로 쓰지 않는다. */
	@Column(name = "label_source", length = 10, nullable = false)
	private String labelSource;

	@Column(name = "menu_label_count", nullable = false)
	private int menuLabelCount;

	@Column(name = "review_label_count", nullable = false)
	private int reviewLabelCount;

	/**
	 * sha256 앞 32자 — 사전 판본 + 신호 스냅샷 id + 쓰인 메뉴 + 리뷰 라벨. 시각은 넣지 않는다.
	 * 같은 값이면 다시 계산해도 같은 벡터라, 재적재가 진짜 바뀐 것인지 가린다.
	 *
	 * <p>컬럼이 {@code CHAR(32)} 라 JDBC 타입을 명시한다. String 의 기본 매핑은 VARCHAR 이고, 운영 설정인
	 * {@code ddl-auto=validate} 는 그 차이에서 기동을 멈춘다(bpchar vs varchar). H2 create-drop 테스트는
	 * 엔티티에서 스키마를 만들기 때문에 이 불일치를 잡지 못한다.
	 */
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "source_fingerprint", length = 32, nullable = false)
	private String sourceFingerprint;

	@Column(name = "dictionary_version", length = 20, nullable = false)
	private String dictionaryVersion;

	/**
	 * 원천(네이버 스냅샷)을 마지막으로 <b>본</b> 때. 모르면 null.
	 *
	 * <p>"마지막으로 바뀐 때" 가 아니다 — 스냅샷은 내용이 달라질 때만 쌓여서, 그 시각을 관측 시각으로 쓰면
	 * 메뉴가 안 바뀐 가게일수록 오래된 것처럼 보인다. 빵집은 메뉴가 몇 달씩 그대로인 게 정상이라,
	 * 오래 장사한 동네 빵집이 가장 심하게 깎인다(2026-09-21 에 실제로 그 값이 들어갔다가 고쳤다).
	 */
	@Column(name = "observed_at")
	private LocalDateTime observedAt;

	/** 배치가 돈 시각. 신선도 판정에 쓸 수 없다 — 그건 observedAt 이다. */
	@Column(name = "computed_at", nullable = false)
	private LocalDateTime computedAt;
}
