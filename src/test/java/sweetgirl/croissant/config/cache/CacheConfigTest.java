package sweetgirl.croissant.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;

import sweetgirl.croissant.course.dto.response.CourseSummaryResponse;
import sweetgirl.croissant.course.enums.CourseType;

class CacheConfigTest {

	@Test
	@DisplayName("공개 코스 목록 record는 타입 힌트 없는 JSON으로 저장했다가 그대로 복원된다")
	void courseSummaryListSerializer_roundTrips() {
		RedisSerializer<List<CourseSummaryResponse>> serializer = CacheConfig.courseSummaryListSerializer();
		List<CourseSummaryResponse> courses = List.of(
			new CourseSummaryResponse(1L, "부산 빵투어", "courses/1/cover.jpg", "resized/320/courses/1/cover.jpg",
				3, List.of(10L, 20L, 30L), List.of("부산 해운대구"), CourseType.CURATED, null, "인기", 12L, 1, 4L),
			new CourseSummaryResponse(2L, "내 코스", null, null, 0, List.of(), List.of(), CourseType.CUSTOM,
				"빵순이", null, 0L, null, 0L));

		byte[] json = serializer.serialize(courses);
		List<CourseSummaryResponse> restored = serializer.deserialize(json);

		assertThat(new String(json)).doesNotContain("@class");
		assertThat(restored).isEqualTo(courses);
	}
}
