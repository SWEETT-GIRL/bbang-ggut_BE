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
	 * 이 가게를 담게 한 추천(상권 추천이든 앵커 추천이든 그 recommendation.id). 직접 담았으면 null.
	 * 한 코스에 추천으로 담은 가게와 직접 담은 가게가 섞이므로 가게 단위로 남긴다. recommendation 모듈과는 id 로만 잇는다.
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

	/** bulk 담기·앵커 담기가 붙인다. 뺐다가 직접 다시 담으면 새 행이라 자연히 null 이다. */
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
