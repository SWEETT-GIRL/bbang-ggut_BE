package sweetgirl.croissant.recommendation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;

class CourseAreaTest {

	private static CourseArea seongsu() {
		return CourseArea.create("서울", "성수동", 5L, 37.5446, 127.0557, 1200, 1200, "성동구", "성수동", 70);
	}

	@Test
	@DisplayName("새 상권은 활성 상태로 만들어지고, 동까지 있으면 반경과 동 일치로 소속을 판정한다")
	void create_seoulDong_isActiveWithRegionRule() {
		CourseArea area = seongsu();

		assertThat(area.isActive()).isTrue();
		assertThat(area.hasRegionRule()).isTrue();
		assertThat(area.coversWholeDistrict()).isFalse();
		assertThat(area.getSearchRegionId()).isEqualTo(5L);
		assertThat(area.getMaxHopM()).isEqualTo(1200);
	}

	@Test
	@DisplayName("서울 밖 도장 칸은 시군구 전체를 상권으로 본다")
	void create_districtWide_coversWholeDistrict() {
		CourseArea suwon = CourseArea.create("경기", "수원시", null, 37.2636, 127.0286, 5000, 8000, "수원시", null, 10);

		assertThat(suwon.hasRegionRule()).isTrue();
		assertThat(suwon.coversWholeDistrict()).isTrue();
	}

	@Test
	@DisplayName("지역 컬럼이 없으면 반경만으로 소속을 판정한다")
	void create_withoutRegion_usesRadiusOnly() {
		CourseArea hannam = CourseArea.create("서울", "한남동", 7L, 37.5345, 127.0054, 1200, 1200, null, null, 40);

		assertThat(hannam.hasRegionRule()).isFalse();
		assertThat(hannam.coversWholeDistrict()).isFalse();
	}

	@Test
	@DisplayName("읍면동만 있고 시군구가 없으면 만들 수 없다")
	void create_rejectsDongWithoutDistrict() {
		assertThatThrownBy(() -> CourseArea.create("서울", "성수동", null, 37.5, 127.0, 1200, 1200, null, "성수동", 0))
			.isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.COURSE_AREA_REGION_INVALID));
	}

	@Test
	@DisplayName("반경이나 구간 상한이 0 이하면 만들 수 없다")
	void create_rejectsNonPositiveDistances() {
		assertThatThrownBy(() -> CourseArea.create("서울", "성수동", null, 37.5, 127.0, 0, 1200, null, null, 0))
			.isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.COURSE_AREA_RADIUS_INVALID));
		assertThatThrownBy(() -> CourseArea.create("서울", "성수동", null, 37.5, 127.0, 1200, -1, null, null, 0))
			.isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.COURSE_AREA_RADIUS_INVALID));
	}

	@Test
	@DisplayName("비활성화한 상권은 다시 활성화할 수 있다")
	void deactivate_thenActivate() {
		CourseArea area = seongsu();

		area.deactivate();
		assertThat(area.isActive()).isFalse();

		area.activate();
		assertThat(area.isActive()).isTrue();
	}
}
