package sweetgirl.croissant.appversion.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 어드민이 정책값을 통째로 바꾼다(부분 수정 아님 — null을 보내면 그 값을 비운다).
 * minSupportedVersion에는 반드시 스토어에 이미 출시된 버전만 넣을 것 — 없는 버전을 넣으면
 * 사용자가 갈 곳이 없어 앱이 벽돌이 된다.
 */
public record AppVersionPolicyUpdateRequest(
		@Schema(description = "이보다 낮으면 하드 블록. null이면 막지 않음", example = "1.3.2") String minSupportedVersion,
		@Schema(description = "이보다 낮으면 소프트 넛지. null이면 안내하지 않음", example = "1.3.3") String latestVersion,
		@Schema(description = "스토어 URL", example = "https://apps.apple.com/kr/app/id6792633374") @Size(max = 512) String storeUrl,
		@Schema(description = "안내 화면 문구(선택)") @Size(max = 500) String message) {
}
