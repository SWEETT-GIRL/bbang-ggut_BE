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

	/**
	 * 클라이언트가 화면에서 본 정류장 id 의 현재 순서(visitOrder 오름차순). 서버가 현재 순서와 비교해 다르면 409.
	 * optional — 구버전 앱은 보내지 않으므로 그때는 집합 검증만 한다.
	 */
	private List<Long> expectedOrder;

	@Getter
	@NoArgsConstructor
	public static class CourseStoreOrderItem {

		private Long courseStoreId;

		// 배정할 일차. 구버전 앱은 보내지 않으므로 전부 null이면 기존 일차를 유지한다.
		private Integer day;
	}
}
