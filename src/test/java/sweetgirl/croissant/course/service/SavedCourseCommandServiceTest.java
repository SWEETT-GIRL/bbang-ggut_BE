package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.SavedCourse;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class SavedCourseCommandServiceTest {

	@Mock
	SavedCourseRepository savedCourseRepository;
	@Mock
	CourseRepository courseRepository;
	@Mock
	MemberRepository memberRepository;

	@InjectMocks
	SavedCourseCommandService savedCourseCommandService;

	// ───────────────────────── saveCourse ─────────────────────────

	@Test
	@DisplayName("saveCourse: 찜 추가 성공")
	void saveCourse_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockPublicCourse(99L);

		given(savedCourseRepository.existsByMemberIdAndCourseId(memberId, courseId)).willReturn(false);
		given(memberRepository.findByIdAndDeletedAtIsNull(memberId)).willReturn(Optional.of(mock(Member.class)));
		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		savedCourseCommandService.saveCourse(memberId, courseId);

		then(savedCourseRepository).should().save(any(SavedCourse.class));
	}

	@Test
	@DisplayName("saveCourse: 이미 찜한 코스 → COURSE_SAVED_ALREADY")
	void saveCourse_duplicate_throwsSavedAlready() {
		Long memberId = 1L;
		Long courseId = 10L;

		given(savedCourseRepository.existsByMemberIdAndCourseId(memberId, courseId)).willReturn(true);

		assertThatThrownBy(() -> savedCourseCommandService.saveCourse(memberId, courseId))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_SAVED_ALREADY.getMessage());
	}

	@Test
	@DisplayName("saveCourse: 자신의 코스 → COURSE_SAVE_SELF")
	void saveCourse_ownCourse_throwsSaveSelf() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockPublicCourse(memberId);

		given(savedCourseRepository.existsByMemberIdAndCourseId(memberId, courseId)).willReturn(false);
		given(memberRepository.findByIdAndDeletedAtIsNull(memberId)).willReturn(Optional.of(mock(Member.class)));
		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> savedCourseCommandService.saveCourse(memberId, courseId))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_SAVE_SELF.getMessage());
	}

	@Test
	@DisplayName("saveCourse: 비공개 코스 → COURSE_FORBIDDEN")
	void saveCourse_privateCourse_throwsForbidden() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockPublicCourse(99L);
		given(course.isPublic()).willReturn(false);

		given(savedCourseRepository.existsByMemberIdAndCourseId(memberId, courseId)).willReturn(false);
		given(memberRepository.findByIdAndDeletedAtIsNull(memberId)).willReturn(Optional.of(mock(Member.class)));
		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> savedCourseCommandService.saveCourse(memberId, courseId))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
	}

	/** 소유자 id가 ownerId인 공개 코스 mock (isPublic 미스텁 시 mock 기본값 false에 걸리므로 명시 스텁) */
	private Course mockPublicCourse(Long ownerId) {
		Course course = mock(Course.class);
		Member owner = mock(Member.class);
		lenient().when(owner.getId()).thenReturn(ownerId);
		lenient().when(course.getMember()).thenReturn(owner);
		lenient().when(course.isPublic()).thenReturn(true);
		return course;
	}

	// ───────────────────────── unsaveCourse ─────────────────────────

	@Test
	@DisplayName("unsaveCourse: 찜 해제 성공")
	void unsaveCourse_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		SavedCourse savedCourse = mock(SavedCourse.class);

		given(savedCourseRepository.findByMemberIdAndCourseId(memberId, courseId))
			.willReturn(Optional.of(savedCourse));

		savedCourseCommandService.unsaveCourse(memberId, courseId);

		then(savedCourseRepository).should().delete(savedCourse);
	}

	@Test
	@DisplayName("unsaveCourse: 찜한 코스 없음 → COURSE_SAVED_NOT_FOUND")
	void unsaveCourse_notFound_throwsSavedNotFound() {
		Long memberId = 1L;
		Long courseId = 10L;

		given(savedCourseRepository.findByMemberIdAndCourseId(memberId, courseId))
			.willReturn(Optional.empty());

		assertThatThrownBy(() -> savedCourseCommandService.unsaveCourse(memberId, courseId))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_SAVED_NOT_FOUND.getMessage());
	}
}
