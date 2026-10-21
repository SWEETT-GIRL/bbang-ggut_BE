package sweetgirl.croissant.course.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

class CourseStoreTest {

	@Test
	@DisplayName("직접 담은 가게는 추천 출처가 없다")
	void create_hasNoRecommendation() {
		Course course = Course.createCustom("코스", mock(Member.class), false);
		CourseStore courseStore = CourseStore.create(course, Store.builder().name("소금집").build(), 1);

		assertThat(courseStore.isRecommended()).isFalse();
		assertThat(courseStore.getRecommendationId()).isNull();
	}

	@Test
	@DisplayName("추천으로 담은 가게는 그 추천의 id 를 갖는다 — 코스 단위가 아니라 가게 단위 출처")
	void markRecommended() {
		Course course = Course.createCustom("코스", mock(Member.class), false);
		CourseStore courseStore = CourseStore.create(course, Store.builder().name("소금집").build(), 1);

		courseStore.markRecommended(99L);

		assertThat(courseStore.isRecommended()).isTrue();
		assertThat(courseStore.getRecommendationId()).isEqualTo(99L);
	}
}
