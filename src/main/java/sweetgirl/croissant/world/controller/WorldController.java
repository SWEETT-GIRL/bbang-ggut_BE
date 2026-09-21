package sweetgirl.croissant.world.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.world.dto.response.MemberWorldResponse;
import sweetgirl.croissant.world.service.WorldCommandService;
import sweetgirl.croissant.world.service.WorldQueryService;

@Tag(name = "[Client] 빵별일지 세계관", description = "행성 · 고도 단계 · 캐릭터 카탈로그")
@RestController
@RequiredArgsConstructor
@RequestMapping("/members/me/world")
public class WorldController {

	private final WorldCommandService worldCommandService;
	private final WorldQueryService worldQueryService;

	@Operation(summary = "내 세계관 상태 조회", description = "빵탑 높이 · 행성 · 고도 · 가진 친구를 내린다. 첫 조회에서 여정이 시작되고 "
		+ "첫 친구가 지급된다.")
	@GetMapping
	public ResponseEntity<ApiResponse<MemberWorldResponse>> getMyWorld(
			@AuthenticationPrincipal Long memberId) {
		worldCommandService.syncProgress(memberId);
		return ResponseEntity.ok(ApiResponse.ok(worldQueryService.getMemberWorld(memberId)));
	}

	@Operation(summary = "데리고 다닐 친구 변경", description = "이미 만난 친구 중에서만 고를 수 있다.")
	@PostMapping("/characters/{code}/select")
	public ResponseEntity<ApiResponse<Void>> selectCharacter(
			@AuthenticationPrincipal Long memberId,
			@PathVariable String code) {
		worldCommandService.selectCharacter(memberId, code);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "첫 친구 연출 확인", description = "처음 본 때를 남긴다. 두 번 불러도 그 때가 덮이지 않는다.")
	@PostMapping("/welcome")
	public ResponseEntity<ApiResponse<Void>> welcome(@AuthenticationPrincipal Long memberId) {
		worldCommandService.welcome(memberId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}
