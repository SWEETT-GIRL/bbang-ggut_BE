package sweetgirl.croissant.recommendation.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.course.dto.request.CourseStoreBulkItem;

/**
 * 추천 결과를 한 번에 담는다 (설계 §6 · #660). 하나라도 못 담으면 전부 되돌린다.
 * 추천 소유 확인은 recommendation 이 하고, 담기 자체는 course 가 한다 — course 는 추천을 모른다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CourseStoreBulkAddRequest {

	public static final int MAX_STORES = 10;

	/** 추천에서 담았을 때만. 내 추천이 아니거나 이 코스의 추천이 아니면 404. 담긴 가게마다 course_stores.recommendation_id 에 남는다. */
	private Long recommendationId;

	@NotEmpty(message = "담을 가게가 없습니다.")
	@Size(max = MAX_STORES, message = "한 번에 {max}곳까지 담을 수 있습니다.")
	@Valid
	private List<CourseStoreBulkItem> stores;
}
