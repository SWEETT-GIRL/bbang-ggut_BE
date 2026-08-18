package sweetgirl.croissant.course.dto.request;

import java.time.LocalDate;

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
}
