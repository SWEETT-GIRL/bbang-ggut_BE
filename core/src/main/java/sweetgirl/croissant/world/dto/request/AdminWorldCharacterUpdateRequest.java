package sweetgirl.croissant.world.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 비워 보낸 칸은 지금 값을 그대로 둔다.
 * 단 {@code description} 은 빈 문자열("")을 보내면 소개를 지운다 — null 은 그대로 둔다는 뜻이라 지울 길이 따로 필요하다.
 */
public record AdminWorldCharacterUpdateRequest(
		@Size(max = 30) String name,
		@Size(max = 100) String description,
		@Size(max = 30) String planetCode,
		@Min(1) Integer sortOrder,
		Boolean mascot,
		Boolean active) {
}
