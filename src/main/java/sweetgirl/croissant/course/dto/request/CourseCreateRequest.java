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

	/**
	 * 공개 여부. 래퍼 타입인 건 Lombok이 만드는 게터 이름 때문이다 —
	 * primitive면 게터가 isPublic()이 되고, Jackson은 그 프로퍼티를 "public"으로 읽는다.
	 * 그러면 필드명 isPublic과 짝이 안 맞아 어느 이름으로 보내도 값이 안 들어오고
	 * 늘 비공개로 만들어졌다. Boolean이면 게터가 getIsPublic()이라 "isPublic"으로 맞는다.
	 * (CourseUpdateRequest가 이미 이 형태고, 그래서 수정은 정상 동작했다.)
	 *
	 * 안 보내는 구버전 앱을 위해 null은 비공개로 본다.
	 */
	private Boolean isPublic;

	/** 여행 지역(시·도, 예: "서울"·"부산"). 구버전 앱은 안 보낸다. */
	@Size(max = 20, message = "지역은 20자 이내여야 합니다.")
	private String region;

	/** 여행 시작·종료일. 둘 다 있거나 둘 다 없어야 하고 시작 ≤ 종료. */
	private LocalDate startDate;

	private LocalDate endDate;
}
