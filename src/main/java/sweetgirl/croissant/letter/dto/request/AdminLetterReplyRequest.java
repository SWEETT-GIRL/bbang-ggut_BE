package sweetgirl.croissant.letter.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 어드민 답장. 사용자보다 길게 쓸 일이 있어 상한이 넉넉하다. */
@Getter
@NoArgsConstructor
public class AdminLetterReplyRequest {

	@NotBlank
	@Size(max = 2000)
	private String body;
}
