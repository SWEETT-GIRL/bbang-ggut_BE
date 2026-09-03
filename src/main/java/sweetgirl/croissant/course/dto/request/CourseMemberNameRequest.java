package sweetgirl.croissant.course.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.course.entity.CourseMember;

/** 초대 수락·이름 변경이 같이 쓴다. 코스 안에서만 쓰는 이름이라 닉네임과 무관하다. */
@Getter
@NoArgsConstructor
public class CourseMemberNameRequest {

	@NotBlank(message = "이름은 필수입니다.")
	@Size(max = CourseMember.DISPLAY_NAME_MAX_LENGTH, message = "이름은 10자 이내여야 합니다.")
	private String displayName;
}
