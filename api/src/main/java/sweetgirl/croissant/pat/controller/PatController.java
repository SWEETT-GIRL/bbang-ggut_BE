package sweetgirl.croissant.pat.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.pat.dto.response.PatListResponse;
import sweetgirl.croissant.pat.service.PatCommandService;
import sweetgirl.croissant.pat.service.PatQueryService;

@Tag(name = "Pat", description = "톡")
@RestController
@RequestMapping("/members")
@RequiredArgsConstructor
public class PatController {

	private final PatCommandService patCommandService;
	private final PatQueryService patQueryService;

	@Operation(summary = "톡 치기", description = "사람마다 하루 1번(KST). 오늘 이미 쳤으면 그대로 200(토스트 없음). "
		+ "게스트 403 MEMBER403_GUEST · 작성 제한 · 정지 403 · 나 자신 400 · 상대 없음 404 · 차단 관계 403.")
	@PostMapping("/{memberId}/pat")
	public ApiResponse<Void> pat(
			@AuthenticationPrincipal Long viewerId,
			@PathVariable Long memberId) {
		patCommandService.pat(viewerId, memberId);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "나에게 톡 친 사람", description = "최근 days 일(기본 7, 최대 30). today 는 홈 한 줄용 요약이고 "
		+ "오늘 받은 게 없으면 null. 같은 사람의 같은 날은 한 줄.")
	@GetMapping("/me/pats")
	public ApiResponse<PatListResponse> getReceived(
			@AuthenticationPrincipal Long viewerId,
			@Parameter(description = "며칠 치를 볼지(최대 30)") @RequestParam(defaultValue = "7") int days) {
		return ApiResponse.ok(patQueryService.getReceived(viewerId, days));
	}
}
