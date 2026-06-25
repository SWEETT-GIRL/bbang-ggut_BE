package sweetgirl.croissant.course.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CourseImageRegisterRequest {

	@NotBlank(message = "AWS 키는 필수입니다.")
	private String awsKey;

	@NotBlank(message = "MIME 타입은 필수입니다.")
	private String mimeType;

	@NotBlank(message = "파일명은 필수입니다.")
	private String name;
}
