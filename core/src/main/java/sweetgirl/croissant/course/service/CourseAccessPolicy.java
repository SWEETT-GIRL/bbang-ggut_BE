package sweetgirl.croissant.course.service;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.course.util.ShareTokenMatcher;

/**
 * 코스 권한 판정 한 곳. 방장·활성 멤버·편집·조회 판정을 서비스마다 복제하지 않고 여기서 한다.
 *
 * <ul>
 * <li>방장: courses.member_id. 큐레이션은 방장이 없다.</li>
 * <li>활성 멤버: course_members 의 살아있는 행(방장 행 포함). 비로그인은 조회 없이 false.</li>
 * <li>편집: 방장 또는 활성 멤버. 큐레이션은 누구도 못 한다 — course_members 에 행을 심어도 공식 추천 코스가 열리면 안 된다.</li>
 * <li>조회: PUBLIC 은 누구나, 아니면 방장·활성 멤버, 링크 공개(UNLISTED)는 토큰이 맞아야. PRIVATE 은 토큰이 맞아도 안 된다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class CourseAccessPolicy {

	private final CourseMemberRepository courseMemberRepository;

	public boolean isOwner(Course course, Long memberId) {
		return memberId != null && course.getMember() != null && course.getMember().getId().equals(memberId);
	}

	public boolean isActiveMember(Course course, Long memberId) {
		return memberId != null
			&& courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(course.getId(), memberId);
	}

	/** CURATED 검사가 소유자 판정보다 먼저다 — 방장 조건을 멤버까지 넓혀도 큐레이션이 열리지 않게. */
	public boolean canEdit(Course course, Long memberId) {
		if (course.getType() == CourseType.CURATED) {
			return false;
		}
		return isOwner(course, memberId) || isActiveMember(course, memberId);
	}

	/** 가게 추가·수정·삭제·순서 변경, 제목·지역·날짜 수정, 초대 생성. */
	public void requireEditable(Course course, Long memberId) {
		if (!canEdit(course, memberId)) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}
	}

	/** 방장 전용 — 코스 삭제 · 공개 범위 전환 · 커버 이미지. */
	public void requireOwner(Course course, Long memberId) {
		if (!isOwner(course, memberId)) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}
	}

	/** 토큰이 없는 경로(따라가기·방문 체크)는 null 을 넘긴다 — 링크 공개 코스라도 제3자는 못 한다. */
	public boolean canView(Course course, Long memberId, String shareToken) {
		return course.isPublic()
			|| isOwner(course, memberId)
			|| isActiveMember(course, memberId)
			|| ShareTokenMatcher.matches(course, shareToken);
	}

	public void requireViewable(Course course, Long memberId, String shareToken) {
		if (!canView(course, memberId, shareToken)) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}
	}
}
