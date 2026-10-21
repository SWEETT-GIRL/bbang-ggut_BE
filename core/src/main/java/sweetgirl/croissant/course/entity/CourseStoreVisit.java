package sweetgirl.croissant.course.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

/**
 * 코스의 한 가게를 "다녀왔다"고 표시한 사건.
 *
 * 예전에는 방문 여부를 저장하지 않고 "그 가게 기록이 하나라도 있으면 방문"으로
 * 매번 계산했다. 그래서 4달 전에 갔던 빵집이 코스에 들어가면 시작하자마자 체크가 켜졌고,
 * 재방문은 아무 변화도 만들지 못했다. 방문을 사건으로 남겨 언제·어느 코스인지를 갖는다.
 *
 * 키를 course_stores.id 가 아니라 (course_id, member_id, store_id) 로 잡은 이유:
 * 가게를 뺐다가 다시 담으면 course_stores 행이 새로 쌓여 id가 바뀐다.
 * 방문은 특정 행이 아니라 "이 코스의 이 가게"에 붙어야 한다.
 */
@Entity
@Table(name = "course_store_visits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseStoreVisit extends BaseTimeEntity {

	@EmbeddedId
	private CourseStoreVisitId id;

	@MapsId("courseId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@MapsId("memberId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@MapsId("storeId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id", nullable = false)
	private Store store;

	// 기록 없이 체크만 하는 경로를 나중에 열 수 있게 nullable 이다. 지금은 항상 채운다.
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "bread_record_id")
	private BreadRecord breadRecord;

	@Column(nullable = false)
	private LocalDateTime visitedAt;

	private LocalDateTime deletedAt;

	private CourseStoreVisit(Course course, Member member, Store store, BreadRecord breadRecord,
			LocalDateTime visitedAt) {
		this.id = new CourseStoreVisitId(course.getId(), member.getId(), store.getId());
		this.course = course;
		this.member = member;
		this.store = store;
		this.breadRecord = breadRecord;
		this.visitedAt = visitedAt;
	}

	public static CourseStoreVisit of(Course course, Member member, Store store, BreadRecord breadRecord,
			LocalDateTime visitedAt) {
		return new CourseStoreVisit(course, member, store, breadRecord, visitedAt);
	}

	/** 같은 가게를 다른 기록으로 다시 체크하면 덮어쓴다. 되살리는 경로도 겸한다. */
	public void relink(BreadRecord breadRecord, LocalDateTime visitedAt) {
		this.breadRecord = breadRecord;
		this.visitedAt = visitedAt;
		this.deletedAt = null;
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}
}
