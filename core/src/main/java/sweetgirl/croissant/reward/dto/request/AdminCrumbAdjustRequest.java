package sweetgirl.croissant.reward.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 어드민 지급·회수.
 *
 * @param requestId 같은 요청의 재전송을 가리는 번호(선택). 같은 번호가 다시 오면 두 번 반영하지 않는다
 */
public record AdminCrumbAdjustRequest(
		@NotNull @Min(1) Integer amount,
		@NotBlank @Size(max = 200) String memo,
		UUID requestId) {
}
