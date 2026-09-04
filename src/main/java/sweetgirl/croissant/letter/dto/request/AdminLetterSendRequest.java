package sweetgirl.croissant.letter.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 어드민이 여러 명에게 같은 편지를 보낸다. 각자 자기 스레드에 한 통씩 꽂히고 답장도 따로 온다.
 *
 * <p>"전체에게"는 일부러 열지 않았다 — 답장이 오는 통로라서, 전체 발송은 공지(알림) 쪽이 맡는다.
 */
@Getter
@NoArgsConstructor
public class AdminLetterSendRequest {

	@NotEmpty
	@Size(max = 500)
	private List<Long> memberIds;

	@NotBlank
	@Size(max = 2000)
	private String body;
}
