package sweetgirl.croissant.course.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 여러 곳 담기의 가게 하나. 배열 순서가 곧 담는 순서다. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CourseStoreBulkItem {

	@NotNull(message = "가게 ID는 필수입니다.")
	private Long storeId;

	/** 담을 일차. 없으면 마지막 일차에 담는다 — 단건 담기와 같은 규칙. */
	private Integer day;
}
