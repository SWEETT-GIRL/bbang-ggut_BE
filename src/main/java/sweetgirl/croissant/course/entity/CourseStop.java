package sweetgirl.croissant.course.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.store.entity.Store;

@Entity
@Table(name = "course_stops", uniqueConstraints = {
	@UniqueConstraint(columnNames = {"course_id", "visit_order"}),
	@UniqueConstraint(columnNames = {"course_id", "store_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseStop extends BaseTimeEntity {

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

	@Column(columnDefinition = "TEXT")
	private String memo;

	private LocalDateTime deletedAt;

	@Builder(access = AccessLevel.PRIVATE)
	private CourseStop(
			Course course,
			Store store,
			Integer visitOrder,
			String memo) {
		this.course = course;
		this.store = store;
		this.visitOrder = visitOrder;
		this.memo = memo;
	}

	public static CourseStop create(
			Course course,
			Store store,
			Integer visitOrder) {
		return CourseStop.builder()
			.course(course)
			.store(store)
			.visitOrder(visitOrder)
			.build();
	}

	public static CourseStop createWithMemo(
			Course course,
			Store store,
			Integer visitOrder,
			String memo) {
		return CourseStop.builder()
			.course(course)
			.store(store)
			.visitOrder(visitOrder)
			.memo(memo)
			.build();
	}

	public void updateOrder(Integer visitOrder) {
		this.visitOrder = visitOrder;
	}

	public void updateMemo(String memo) {
		this.memo = memo;
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}
}
