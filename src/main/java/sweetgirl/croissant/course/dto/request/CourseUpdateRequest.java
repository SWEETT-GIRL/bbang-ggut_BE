package sweetgirl.croissant.course.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CourseUpdateRequest {

	@Size(max = 100, message = "코스 제목은 100자 이내여야 합니다.")
	private String title;

	private Boolean isPublic;

	@Size(max = 20, message = "지역은 20자 이내여야 합니다.")
	private String region;

	/** 시작·종료일은 한 쌍으로만 바뀐다 — 둘 다 보내야 반영된다. */
	private LocalDate startDate;

	private LocalDate endDate;

	/** 빈 일차를 포함한 일차 수. 늘어나기만 한다 — 현재 값이나 가게가 있는 마지막 일차보다 작으면 400. */
	@Min(value = 1, message = "일차 수는 1 이상이어야 합니다.")
	private Integer dayCount;
}
