package sweetgirl.croissant.member.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.member.dto.response.CarryOverResponse;
import sweetgirl.croissant.member.service.CarryOverQueryService;

@Tag(name = "[Client] 로그인 안내", description = "게스트가 로그인하면 따라올 것들")
@RestController
@RequiredArgsConstructor
@RequestMapping("/members/me")
public class CarryOverController {

	private final CarryOverQueryService carryOverQueryService;

	@Operation(summary = "로그인하면 따라올 것 조회", description = "로그인 게이트 문구를 고르는 데 쓴다. 쌓아둔 게 있는 게스트에게는 숫자를 넣은 "
		+ "문구를, 방금 들어온 게스트에게는 평범한 안내를 띄우기 위해서다.")
	@GetMapping("/carry-over")
	public ResponseEntity<ApiResponse<CarryOverResponse>> getCarryOver(
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(carryOverQueryService.getCarryOver(memberId)));
	}
}
