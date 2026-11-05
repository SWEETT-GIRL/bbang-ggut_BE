package sweetgirl.croissant.course.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.DynamicUpdate;

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
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;

/**
 * 코스 정류장에 사람마다 하나씩 붙는 메모.
 *
 * 키가 정류장 행(course_stores.id)이라 가게를 코스에서 빼면 정류장 행과 함께 전원 메모가 닫히고,
 * 다시 담아도(새 정류장 행) 돌아오지 않는다. 방문 체크가 (course_id, store_id) 로 "남기되 숨김"인 것과 다르다.
 * 큐레이션 코스는 이 테이블을 쓰지 않는다 — course_stores.memo 가 코스 설명 역할로 남는다.
 */
@Entity
@Table(name = "course_store_memos")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseStoreMemo extends BaseTimeEntity {

	/** DB VARCHAR(200) 과 요청 DTO 의 @Size 가 같은 값을 본다. 방문계획 메모와 같은 제한. */
	public static final int CONTENT_MAX_LENGTH = 200;

	@EmbeddedId
	private CourseStoreMemoId id;

	@MapsId("courseStoreId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_store_id", nullable = false)
	private CourseStore courseStore;

	@MapsId("memberId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(nullable = false, length = CONTENT_MAX_LENGTH)
	private String content;

	/** 가게 뺄 때 · 나가기 · 탈퇴. */
	private LocalDateTime deletedAt;

	private CourseStoreMemo(CourseStore courseStore, Member member, String content) {
		this.id = new CourseStoreMemoId(courseStore.getId(), member.getId());
		this.courseStore = courseStore;
		this.member = member;
		this.content = content;
	}

	public static CourseStoreMemo of(CourseStore courseStore, Member member, String content) {
		return new CourseStoreMemo(courseStore, member, content);
	}

	public void updateContent(String content) {
		this.content = content;
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}

	/** 지웠던 메모를 같은 PK 로 다시 쓴다 — 새 행이 아니다. */
	public void restore(String content) {
		this.content = content;
		this.deletedAt = null;
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}
}
