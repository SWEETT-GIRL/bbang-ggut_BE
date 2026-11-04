package sweetgirl.croissant.course.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.event.CourseChangedEvent;
import sweetgirl.croissant.course.repository.CourseRepository;

/**
 * 코스 version(+updated_at) 을 올리는 유일한 문. 코스·가게·메모·멤버를 바꾸는 쓰기는 전부 여기를 지난다.
 * 올리면서 {@link CourseChangedEvent} 를 내 커밋 뒤 일행에게 VERSION 신호가 간다 — 호출부가 늘어도 신호가 빠지지 않는다.
 * {@code courseRepository.bumpVersion} 을 직접 부르는 곳은 이 클래스 밖에 없어야 한다.
 */
@Component
@RequiredArgsConstructor
public class CourseVersionBumper {

	private final CourseRepository courseRepository;
	private final ApplicationEventPublisher eventPublisher;

	public void bump(Long courseId) {
		courseRepository.bumpVersion(courseId);
		eventPublisher.publishEvent(new CourseChangedEvent(courseId));
	}
}
