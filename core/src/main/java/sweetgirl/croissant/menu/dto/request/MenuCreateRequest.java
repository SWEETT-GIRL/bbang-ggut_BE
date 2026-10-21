package sweetgirl.croissant.menu.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MenuCreateRequest(
		@NotBlank(message = "메뉴명은 필수입니다.") @Size(max = 100, message = "메뉴명은 100자 이하여야 합니다.") String name) {
}
