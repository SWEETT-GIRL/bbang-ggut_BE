package sweetgirl.croissant.course.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.member.entity.Member;

class CourseProgressTest {

	@Test
	@DisplayName("중단한 코스를 재시작해도 최초 시작 시각을 유지한다")
	void resume_keepsInitialStartedAt() {
		Member member = Member.builder().nickname("테스터").build();
		Course course = Course.createCurated("성수 빵지순례", null);
		ReflectionTestUtils.setField(member, "id", 7L);
		ReflectionTestUtils.setField(course, "id", 10L);
		LocalDateTime startedAt = LocalDateTime.of(2026, 8, 1, 10, 0);
		CourseProgress progress = CourseProgress.start(member, course, startedAt);

		progress.stop(LocalDateTime.of(2026, 8, 2, 10, 0));
		progress.resume();

		assertThat(progress.getId()).isEqualTo(new CourseProgressId(7L, 10L));
		assertThat(progress.getStartedAt()).isEqualTo(startedAt);
		assertThat(progress.getStoppedAt()).isNull();
	}

	@Test
	@DisplayName("완료 조건을 잃으면 완료 시각을 해제한다")
	void markIncomplete_clearsCompletedAt() {
		Member member = Member.builder().nickname("테스터").build();
		Course course = Course.createCurated("성수 빵지순례", null);
		ReflectionTestUtils.setField(member, "id", 7L);
		ReflectionTestUtils.setField(course, "id", 10L);
		CourseProgress progress = CourseProgress.start(member, course, LocalDateTime.now());

		progress.markCompleted(LocalDateTime.now());
		progress.markIncomplete();

		assertThat(progress.getCompletedAt()).isNull();
	}
}
