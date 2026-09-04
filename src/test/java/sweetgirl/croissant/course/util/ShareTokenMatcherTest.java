package sweetgirl.croissant.course.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.enums.CourseVisibility;
import sweetgirl.croissant.member.entity.Member;

@DisplayName("ShareTokenMatcher")
class ShareTokenMatcherTest {

	private Course courseWith(CourseVisibility visibility, String shareToken) {
		Course course = Course.createCustom("코스", mock(Member.class), false);
		course.updateVisibility(visibility);
		if (shareToken != null) {
			course.issueShareToken(shareToken);
		}
		return course;
	}

	@Test
	@DisplayName("UNLISTED 이고 토큰이 같을 때만 true — 접두사·길이만 다른 토큰, null 은 false")
	void matches_onlyUnlistedWithSameToken() {
		assertThat(ShareTokenMatcher.matches(courseWith(CourseVisibility.UNLISTED, "abc"), "abc")).isTrue();
		assertThat(ShareTokenMatcher.matches(courseWith(CourseVisibility.UNLISTED, "abc"), "abd")).isFalse();
		assertThat(ShareTokenMatcher.matches(courseWith(CourseVisibility.UNLISTED, "abc"), "ab")).isFalse();
		assertThat(ShareTokenMatcher.matches(courseWith(CourseVisibility.UNLISTED, "abc"), null)).isFalse();
		assertThat(ShareTokenMatcher.matches(courseWith(CourseVisibility.UNLISTED, null), "abc")).isFalse();
	}

	@Test
	@DisplayName("PRIVATE 은 토큰이 맞아도 false — 토큰보다 PRIVATE 이 강하다. PUBLIC 은 이 판정을 타지 않는다")
	void matches_privateAndPublic_false() {
		assertThat(ShareTokenMatcher.matches(courseWith(CourseVisibility.PRIVATE, "abc"), "abc")).isFalse();
		assertThat(ShareTokenMatcher.matches(courseWith(CourseVisibility.PUBLIC, "abc"), "abc")).isFalse();
	}
}
