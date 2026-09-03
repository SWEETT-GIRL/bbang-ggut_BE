package sweetgirl.croissant.letter.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.letter.enums.LetterCategory;

/** 사용자가 보내는 편지. 제목은 없다 — 대화라서 말만 있으면 된다. */
@Getter
@NoArgsConstructor
public class LetterSendRequest {

	@NotBlank
	@Size(max = 1000)
	private String body;

	/**
	 * 문의 유형. 말을 처음 걸 때 고르고, 이어지는 메시지에도 앱이 같은 값을 실어 보낸다.
	 * 없이 와도 받는다 — 유형을 못 고르는 옛 앱이 있을 수 있다.
	 */
	private LetterCategory category;
}
