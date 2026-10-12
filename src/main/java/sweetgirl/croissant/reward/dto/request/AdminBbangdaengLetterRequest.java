package sweetgirl.croissant.reward.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 빵댕이 편지 글 쓰기·고치기.
 *
 * @param active 고칠 때만 본다. 안 보내면 켠 채로 둔다
 */
public record AdminBbangdaengLetterRequest(
		@NotBlank @Size(max = 500) String body,
		Boolean active) {
}
