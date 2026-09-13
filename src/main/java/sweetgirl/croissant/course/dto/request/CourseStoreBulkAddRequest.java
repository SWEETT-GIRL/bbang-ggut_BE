package sweetgirl.croissant.course.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 추천 결과를 한 번에 담는다 (설계 §6 · #660). 하나라도 못 담으면 전부 되돌린다. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CourseStoreBulkAddRequest {

	public static final int MAX_STORES = 10;

	/** 추천에서 담았을 때만. 담긴 가게마다 course_stores.recommendation_id 에 남는다 — 코스 단위 출처 칸은 없다(#731). */
	private Long recommendationId;

	/** 배열 순서가 곧 담는 순서다. */
	@NotEmpty(message = "담을 가게가 없습니다.")
	@Size(max = MAX_STORES, message = "한 번에 {max}곳까지 담을 수 있습니다.")
	@Valid
	private List<Item> stores;

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Item {

		@NotNull(message = "가게 ID는 필수입니다.")
		private Long storeId;

		/** 담을 일차. 없으면 마지막 일차에 담는다 — 단건 담기와 같은 규칙. */
		private Integer day;
	}
}
