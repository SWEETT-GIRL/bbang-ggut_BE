package sweetgirl.croissant.world.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 새 캐릭터. {@code code} 는 앱의 CharacterId 와 1:1 이고, <b>이름의 정본은 이 표다</b> —
 * 어드민에서 고치면 앱 배포 없이 바뀐다.
 */
public record AdminWorldCharacterCreateRequest(
		@NotBlank @Size(max = 30) String code,
		@NotBlank @Size(max = 30) String name,
		@NotBlank @Size(max = 30) String planetCode,
		@NotNull @Min(1) Integer sortOrder,
		/** 첫 설치 때 바로 주는 한 명. 참으로 두면 지금 마스코트가 자리를 내준다. */
		Boolean mascot) {
}
