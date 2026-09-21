package sweetgirl.croissant.course.entity;

import java.time.LocalDateTime;
import java.time.LocalTime;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.store.entity.Store;

@Entity
@Table(name = "course_stores")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseStore extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id", nullable = false)
	private Store store;

	@Column(name = "visit_order", nullable = false)
	private Integer visitOrder;

	@Column(name = "day", nullable = false)
	private Integer day;

	@Column(columnDefinition = "TEXT")
	private String memo;

	@Column(name = "visit_time")
	private LocalTime visitTime;

	/**
	 * 이 가게를 담게 한 추천의 recommendation.id. 직접 담았으면 null. <b>추천 출처는 가게 단위로만 남긴다</b> —
	 * 코스 단위 칸은 두지 않는다. 한 코스가 일차마다, 상권마다 여러 추천을 받으므로 한 칸에는 담기지 않고,
	 * "이 코스에 요청된 추천 전부"는 recommendation.course_id 가 이미 갖고 있다.
	 * 지금은 상권 추천의 bulk 담기만 이 값을 붙인다. 앵커 추천은 FE 가 기존 단건 담기에 가게 id 만 보내서 null 로 남고,
	 * 그 출처는 recommendation_feedback 의 SAVE 행으로 복원한다.
	 * 소프트 삭제라 사용자가 뺀 가게도 이 값을 들고 남는다 — "이 코스가 추천을 쓴 적 있나"까지 여기서 나온다.
	 * recommendation 모듈과는 id 로만 잇는다.
	 */
	@Column(name = "recommendation_id")
	private Long recommendationId;

	private LocalDateTime deletedAt;

	@Builder(access = AccessLevel.PRIVATE)
	private CourseStore(
			Course course,
			Store store,
			Integer visitOrder,
			Integer day,
			String memo,
			LocalTime visitTime) {
		this.course = course;
		this.store = store;
		this.visitOrder = visitOrder;
		this.day = day;
		this.memo = memo;
		this.visitTime = visitTime;
	}

	public static CourseStore create(
			Course course,
			Store store,
			Integer visitOrder) {
		return CourseStore.builder()
			.course(course)
			.store(store)
			.visitOrder(visitOrder)
			.day(1)
			.build();
	}

	public static CourseStore createWithDetails(
			Course course,
			Store store,
			Integer visitOrder,
			Integer day,
			String memo,
			LocalTime visitTime) {
		return CourseStore.builder()
			.course(course)
			.store(store)
			.visitOrder(visitOrder)
			.day(day)
			.memo(memo)
			.visitTime(visitTime)
			.build();
	}

	public void updateOrder(Integer visitOrder) {
		this.visitOrder = visitOrder;
	}

	public void updateDay(Integer day) {
		this.day = day;
	}

	public void updateMemo(String memo) {
		this.memo = memo;
	}

	public void updateVisitTime(LocalTime visitTime) {
		this.visitTime = visitTime;
	}

	/** 상권 추천의 bulk 담기가 붙인다. 뺐다가 직접 다시 담으면 새 행이라 자연히 null 이다. */
	public void markRecommended(Long recommendationId) {
		this.recommendationId = recommendationId;
	}

	public boolean isRecommended() {
		return this.recommendationId != null;
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}
}
