package sweetgirl.croissant.personalization.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.personalization.dto.request.BbtiQuizRequest;
import sweetgirl.croissant.personalization.dto.response.BbtiResultResponse;
import sweetgirl.croissant.personalization.service.TasteQuizCommandService;

@Tag(name = "BBTI", description = "빵BTI 검사 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/users/me/bbti")
public class BbtiController {

	private final TasteQuizCommandService tasteQuizCommandService;

	@Operation(summary = "빵BTI 검사 응답 저장", description = "4문항(축마다 하나) 또는 8문항(축마다 둘). 선택지는 축마다 갈래 이름으로 보낸다 — "
		+ "맛 SWEET·PLAIN·SALTY·AROMA, 식감 SOFT·CRISP·CHEWY, 속 FULL·SIMPLE, 스타일 CLASSIC·TRENDY. "
		+ "그 사용자의 취향 프로필이 즉시 다시 계산된다.")
	@PostMapping
	public ResponseEntity<ApiResponse<BbtiResultResponse>> submit(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody BbtiQuizRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(tasteQuizCommandService.submit(memberId, request)));
	}
}
