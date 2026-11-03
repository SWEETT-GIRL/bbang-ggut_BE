package sweetgirl.croissant.course.service;

import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import sweetgirl.croissant.course.event.CourseChangedEvent;
import sweetgirl.croissant.course.repository.CourseRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("CourseVersionBumper")
class CourseVersionBumperTest {

	@Mock
	private CourseRepository courseRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	@InjectMocks
	private CourseVersionBumper courseVersionBumper;

	@Test
	@DisplayName("version 을 올리고 CourseChangedEvent 를 낸다 — 커밋 뒤 VERSION 신호의 유일한 출처")
	void bump_incrementsAndPublishes() {
		courseVersionBumper.bump(10L);

		then(courseRepository).should().bumpVersion(10L);
		then(eventPublisher).should().publishEvent(new CourseChangedEvent(10L));
	}
}
