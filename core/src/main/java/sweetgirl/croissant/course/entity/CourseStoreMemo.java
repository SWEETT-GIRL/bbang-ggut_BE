package sweetgirl.croissant.course.entity;

import java.time.Duration;
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

	/** seq 가 서버 시각보다 이만큼 넘게 미래면 시계가 크게 틀린 기기다 — 받으면 그 필드가 영영 그 값 아래로 못 돌아온다. */
	public static final Duration SEQ_MAX_AHEAD = Duration.ofDays(1);

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

	/**
	 * 순서 기준. 앱이 (정류장 × 회원) 필드마다 만드는 하이브리드 논리 시계 — 초안 버퍼에서 오는 글과 PATCH 로 바로 오는 글을
	 * 같은 자로 비교한다. 0 은 seq 를 모르던 옛 행: 어떤 새 seq 보다 작아 다음 글이 그대로 반영된다.
	 */
	@Column(nullable = false)
	private long seq;

	/** 가게 뺄 때 · 나가기 · 탈퇴 · 빈 내용으로 지우기. */
	private LocalDateTime deletedAt;

	private CourseStoreMemo(CourseStore courseStore, Member member, String content, long seq) {
		this.id = new CourseStoreMemoId(courseStore.getId(), member.getId());
		this.courseStore = courseStore;
		this.member = member;
		this.content = content;
		this.seq = seq;
	}

	public static CourseStoreMemo of(CourseStore courseStore, Member member, String content, long seq) {
		return new CourseStoreMemo(courseStore, member, content, seq);
	}

	/**
	 * 더 새로운 글만 반영한다 — 초안 플러시와 PATCH 가 함께 쓰는 쓰기 경로.
	 * 빈 내용은 지우기, 지워진 행에 글이 오면 되살리기. seq 가 작거나 같으면 아무것도 바꾸지 않는다.
	 * 지운 뒤에도 seq 는 남으므로, 뒤늦게 도착한 옛 글이 지운 메모를 되살리지 못한다.
	 *
	 * @return 반영했으면 true
	 */
	public boolean applyIfNewer(String content, long seq) {
		if (seq <= this.seq) {
			return false;
		}
		this.seq = seq;
		if (content == null || content.isBlank()) {
			if (!isDeleted()) {
				softDelete();
			}
			return true;
		}
		this.content = content;
		this.deletedAt = null;
		return true;
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}
}
