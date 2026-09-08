package sweetgirl.croissant.menu.dto.request;

import jakarta.validation.constraints.NotBlank;

/** @param kind MenuKind 이름 — AI 제안 칩에서 고른 값 */
public record MenuKindConfirmRequest(
		@NotBlank(message = "메뉴 종류는 필수입니다.") String kind) {
}
