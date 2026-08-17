package sweetgirl.croissant.course.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CourseCreateRequest {

	@NotBlank(message = "코스 제목은 필수입니다.")
	@Size(max = 100, message = "코스 제목은 100자 이내여야 합니다.")
	private String title;

	private boolean isPublic;

	/** 여행 지역(시·도, 예: "서울"·"부산"). 구버전 앱은 안 보낸다. */
	@Size(max = 20, message = "지역은 20자 이내여야 합니다.")
	private String region;

	/** 여행 시작·종료일. 둘 다 있거나 둘 다 없어야 하고 시작 ≤ 종료. */
	private LocalDate startDate;

	private LocalDate endDate;
}
