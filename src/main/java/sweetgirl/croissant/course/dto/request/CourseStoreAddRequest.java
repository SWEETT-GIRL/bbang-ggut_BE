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

	// 담을 일차. 없으면 마지막 일차에 담는다.
	private Integer day;

	private String memo;

	private LocalTime visitTime;
}
