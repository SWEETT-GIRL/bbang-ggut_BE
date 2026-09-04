package sweetgirl.croissant.course.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.course.enums.CourseVisibility;
import sweetgirl.croissant.member.entity.Member;

@DisplayName("Course")
class CourseTest {

	@Test
	@DisplayName("만들 때 visibility 는 is_public 을 따라가고 일차 수는 1로 시작한다")
	void create_derivesVisibilityAndDefaultDayCount() {
		Course publicCourse = Course.createCustom("공개", mock(Member.class), true);
		Course privateCourse = Course.createCustom("비공개", mock(Member.class), false);
		Course forked = Course.createForked("copy", mock(Member.class), 1L);
		Course curated = Course.createCurated("큐레이션", null);

		assertThat(publicCourse.getVisibility()).isEqualTo(CourseVisibility.PUBLIC);
		assertThat(privateCourse.getVisibility()).isEqualTo(CourseVisibility.PRIVATE);
		assertThat(forked.getVisibility()).isEqualTo(CourseVisibility.PRIVATE);
		assertThat(curated.getVisibility()).isEqualTo(CourseVisibility.PUBLIC);
		assertThat(publicCourse.getDayCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("updateVisibility 는 is_public 도 같이 쓴다 — PUBLIC 일 때만 true, UNLISTED·PRIVATE 은 false")
	void updateVisibility_syncsIsPublic() {
		Course course = Course.createCustom("코스", mock(Member.class), true);

		course.updateVisibility(CourseVisibility.UNLISTED);
		assertThat(course.isPublic()).isFalse();
		assertThat(course.getVisibility()).isEqualTo(CourseVisibility.UNLISTED);

		course.updateVisibility(CourseVisibility.PRIVATE);
		assertThat(course.isPublic()).isFalse();

		course.updateVisibility(CourseVisibility.PUBLIC);
		assertThat(course.isPublic()).isTrue();
		assertThat(course.getVisibility()).isEqualTo(CourseVisibility.PUBLIC);
	}

	@Test
	@DisplayName("issueShareToken 은 처음 한 번만 — 공개 범위를 오가도 토큰은 보존된다")
	void issueShareToken_onlyOnce() {
		Course course = Course.createCustom("코스", mock(Member.class), false);
		assertThat(course.getShareToken()).isNull();

		course.issueShareToken("first");
		course.updateVisibility(CourseVisibility.PUBLIC);
		course.updateVisibility(CourseVisibility.UNLISTED);
		course.issueShareToken("second");

		assertThat(course.getShareToken()).isEqualTo("first");
	}

	@Test
	@DisplayName("ensureDayCountAtLeast 는 올리기만 하고 줄이지 않는다")
	void ensureDayCountAtLeast_onlyGrows() {
		Course course = Course.createCustom("코스", mock(Member.class), false);

		course.ensureDayCountAtLeast(3);
		assertThat(course.getDayCount()).isEqualTo(3);

		course.ensureDayCountAtLeast(2);
		assertThat(course.getDayCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("changeOwner 는 방장을 바꾼다")
	void changeOwner_replacesMember() {
		Member owner = mock(Member.class);
		Member successor = mock(Member.class);
		Course course = Course.createCustom("코스", owner, false);

		course.changeOwner(successor);

		assertThat(course.getMember()).isSameAs(successor);
	}
}
