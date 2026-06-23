package sweetgirl.croissant.course.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.SavedCourse;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class SavedCourseCommandService {

	private final SavedCourseRepository savedCourseRepository;
	private final CourseRepository courseRepository;
	private final MemberRepository memberRepository;

	public void saveCourse(Long memberId, Long courseId) {
		if (savedCourseRepository.existsByMemberIdAndCourseId(memberId, courseId)) {
			throw new CourseException(CourseErrorCode.COURSE_SAVED_ALREADY);
		}
		Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		savedCourseRepository.save(SavedCourse.create(member, course));
	}

	public void unsaveCourse(Long memberId, Long courseId) {
		SavedCourse savedCourse = savedCourseRepository.findByMemberIdAndCourseId(memberId, courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_SAVED_NOT_FOUND));
		savedCourseRepository.delete(savedCourse);
	}
}
