package sweetgirl.croissant.recommendation.entity;

import java.time.LocalDateTime;

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
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;

/**
 * 사용자가 가게·빵에 남긴 신호 한 건 (설계 §3.1). 매일 04:00 배치(#663)가 전날분을 적재한다.
 *
 * 사실만 적고 해석은 배치가 한다. 가중치는 저장하지 않는다 — 반감기·선택 성향 보정이 시점마다 달라 계산 때 곱해야 하고,
 * 유형별 기본 가중치는 type 과 1:1 이라 배치의 enum 이 갖는다. 종류(menu_category)도 복사하지 않고 menu 와 조인해 읽는다.
 * occurredAt 은 사건 시각, createdAt 은 적재 시각이다. 반감기(90일)는 사건 시각 기준이다.
 * 로그성 테이블이라 member·store·menu 와 연관을 맺지 않고, 한 번 쓰면 바꾸지 않는다.
 */
@Entity
@Table(name = "user_store_interaction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserStoreInteraction {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "store_id")
	private Long storeId;

	/** 기록의 빵이 가리키는 가게 메뉴. 가게 단위 신호면 null. */
	@Column(name = "menu_id")
	private Long menuId;

	/** 값 체계는 #663 의 InteractionType enum 이 정한다. */
	@Column(nullable = false, length = 24)
	private String type;

	/** 어디서 생긴 신호인지 (예: CURATION, SEARCH, RECO). */
	@Column(length = 24)
	private String context;

	/** 같은 세션에서 저장·담기로 이어졌는지. 모르면 null. */
	private Boolean converted;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder(access = AccessLevel.PRIVATE)
	private UserStoreInteraction(
			Long memberId,
			Long storeId,
			Long menuId,
			String type,
			String context,
			Boolean converted,
			LocalDateTime occurredAt) {
		this.memberId = memberId;
		this.storeId = storeId;
		this.menuId = menuId;
		this.type = type;
		this.context = context;
		this.converted = converted;
		this.occurredAt = occurredAt;
		this.createdAt = LocalDateTime.now();
	}

	public static UserStoreInteraction of(
			Long memberId,
			Long storeId,
			Long menuId,
			String type,
			String context,
			Boolean converted,
			LocalDateTime occurredAt) {
		if (storeId == null && menuId == null) {
			throw new CustomException(RecommendationErrorCode.INTERACTION_TARGET_REQUIRED);
		}
		return UserStoreInteraction.builder()
			.memberId(memberId)
			.storeId(storeId)
			.menuId(menuId)
			.type(type)
			.context(context)
			.converted(converted)
			.occurredAt(occurredAt)
			.build();
	}

	/** 빵 단위 신호인지 (기록의 빵). 가게 단위 신호는 menuId 가 없다. */
	public boolean isBreadLevel() {
		return menuId != null;
	}
}
