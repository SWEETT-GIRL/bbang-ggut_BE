package sweetgirl.croissant.world.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 캐릭터 그림을 올릴 자리를 받아 간다. 키는 서버가 조립한다 —
 * {@code world/characters/{code}/{pose}_v{n}.{ext}} 꼴이고 {@code n} 은 지금 올라간 것 다음 번호다.
 *
 * <p>번호를 붙이는 이유: 같은 키를 덮으면 CDN 과 앱이 옛 그림을 계속 쓴다. 새 키면 그 일이 없다.
 */
public record AdminWorldAssetUploadRequest(
		@NotBlank @Size(max = 20) @Pattern(regexp = "[a-z0-9_-]+") String pose,
		@NotBlank @Size(max = 50) String mimeType) {
}
