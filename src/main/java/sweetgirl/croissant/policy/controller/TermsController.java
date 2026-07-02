package sweetgirl.croissant.policy.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.policy.dto.response.TermsResponse;
import sweetgirl.croissant.policy.service.TermsQueryService;

@Tag(name = "Terms", description = "약관/정책 API")
@RestController
@RequestMapping("/terms")
@RequiredArgsConstructor
public class TermsController {

	private final TermsQueryService termsQueryService;

	@Operation(summary = "약관/정책 목록 조회", description = "회원가입 및 심사용 약관/정책 목록을 반환합니다.")
	@GetMapping
	public ApiResponse<List<TermsResponse>> getTerms() {
		return ApiResponse.ok(termsQueryService.getTerms());
	}
}
