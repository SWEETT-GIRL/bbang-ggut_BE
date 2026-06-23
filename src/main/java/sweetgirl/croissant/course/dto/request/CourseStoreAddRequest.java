package sweetgirl.croissant.course.dto.request;

import java.time.LocalTime;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CourseStoreAddRequest {

	@NotNull(message = "가게 ID는 필수입니다.")
	private Long storeId;

	private String memo;

	private LocalTime visitTime;

	private Integer walkingMinutesToNext;
}
