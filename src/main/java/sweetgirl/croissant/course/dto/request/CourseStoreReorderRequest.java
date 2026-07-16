package sweetgirl.croissant.course.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CourseStoreReorderRequest {

	@NotEmpty(message = "순서 변경 목록은 필수입니다.")
	private List<CourseStoreOrderItem> courseStoreOrders;

	@Getter
	@NoArgsConstructor
	public static class CourseStoreOrderItem {

		private Long courseStoreId;
	}
}
