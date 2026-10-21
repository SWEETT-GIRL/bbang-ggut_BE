package sweetgirl.croissant.inquiry.controller;

import org.springframework.http.HttpStatus;
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
import sweetgirl.croissant.inquiry.dto.request.InquiryCreateRequest;
import sweetgirl.croissant.inquiry.dto.response.InquiryCreateResponse;
import sweetgirl.croissant.inquiry.service.InquiryCommandService;

@Tag(name = "Inquiry", description = "문의 API")
@RestController
@RequestMapping("/inquiries")
@RequiredArgsConstructor
public class InquiryController {

	private final InquiryCommandService inquiryCommandService;

	@Operation(summary = "문의 등록", description = "사용자 문의를 등록합니다. category: STORE, ACCOUNT, BUG, ETC")
	@PostMapping
	public ResponseEntity<ApiResponse<InquiryCreateResponse>> createInquiry(
			@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid InquiryCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(inquiryCommandService.createInquiry(memberId, request)));
	}
}
