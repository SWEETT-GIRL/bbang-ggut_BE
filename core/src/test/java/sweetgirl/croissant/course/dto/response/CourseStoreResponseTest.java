package sweetgirl.croissant.course.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreCategory;

class CourseStoreResponseTest {

	private static final GeometryFactory GEOMETRY = new GeometryFactory(new PrecisionModel(), 4326);

	@Test
	@DisplayName("빵투어 가게 줄에 가게 분류를 싣는다 — 못 받은 가게는 null")
	void carriesStoreCategory() {
		Course course = Course.createCustom("코스", mock(Member.class), false);
		Store bagel = Store.builder().name("코끼리베이글").category(StoreCategory.BAGEL)
			.location(GEOMETRY.createPoint(new Coordinate(127.0, 37.5))).build();
		Store unknown = Store.builder().name("동네 빵집")
			.location(GEOMETRY.createPoint(new Coordinate(127.0, 37.5))).build();

		assertThat(CourseStoreResponse.from(CourseStore.create(course, bagel, 1)).category())
			.isEqualTo(StoreCategory.BAGEL);
		assertThat(CourseStoreResponse.from(CourseStore.create(course, unknown, 2)).category()).isNull();
	}
}
