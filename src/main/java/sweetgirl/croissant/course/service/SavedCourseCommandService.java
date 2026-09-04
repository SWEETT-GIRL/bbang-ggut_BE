package sweetgirl.croissant.course.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.event.CourseSavedEvent;
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
	private final ApplicationEventPublisher eventPublisher;
	private final CourseAccessPolicy courseAccessPolicy;

	public void saveCourse(Long memberId, Long courseId) {
		if (savedCourseRepository.existsByMemberIdAndCourseId(memberId, courseId)) {
			throw new CourseException(CourseErrorCode.COURSE_SAVED_ALREADY);
		}
		Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		if (courseAccessPolicy.isOwner(course, memberId)) {
			throw new CourseException(CourseErrorCode.COURSE_SAVE_SELF);
		}
		if (!course.isPublic()) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}
		savedCourseRepository.save(SavedCourse.create(member, course));
		// 코스 주인에게 "누가 내 빵투어를 찜했다"를 알린다. 커밋된 뒤에 적재되므로
		// 알림이 실패해도 찜하기는 그대로 남는다.
		// 주인 없는 코스(어드민이 만든 큐레이션)는 알릴 사람이 없다 — 위 자기코스 검사가
		// null을 통과시키는 것과 같은 이유다.
		if (course.getMember() != null) {
			eventPublisher.publishEvent(new CourseSavedEvent(courseId, memberId, course.getMember().getId()));
		}
	}

	public void unsaveCourse(Long memberId, Long courseId) {
		SavedCourse savedCourse = savedCourseRepository.findByMemberIdAndCourseId(memberId, courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_SAVED_NOT_FOUND));
		savedCourseRepository.delete(savedCourse);
	}
}
