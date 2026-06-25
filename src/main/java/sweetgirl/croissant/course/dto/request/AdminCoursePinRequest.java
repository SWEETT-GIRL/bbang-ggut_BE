package sweetgirl.croissant.course.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AdminCoursePinRequest {

	@NotNull(message = "핀 순위는 필수입니다.")
	@Min(value = 1, message = "핀 순위는 1 이상이어야 합니다.")
	@Max(value = 3, message = "핀 순위는 3 이하여야 합니다.")
	private Integer pinOrder;
}
