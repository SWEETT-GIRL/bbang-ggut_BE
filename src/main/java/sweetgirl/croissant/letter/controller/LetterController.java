package sweetgirl.croissant.letter.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.letter.dto.request.LetterSendRequest;
import sweetgirl.croissant.letter.dto.response.LetterListResponse;
import sweetgirl.croissant.letter.dto.response.LetterMessageResponse;
import sweetgirl.croissant.letter.dto.response.LetterUnreadCountResponse;
import sweetgirl.croissant.letter.service.LetterService;

@Tag(name = "Letter", description = "편지함 API — 개발자와 사용자가 주고받는 1:1 대화")
@RestController
@RequiredArgsConstructor
@RequestMapping("/letters")
public class LetterController {

	private final LetterService letterService;

	@Operation(summary = "내 편지 목록 조회", description = "개발자와 주고받은 편지를 최신순으로 반환합니다. 편지가 오간 적 없으면 빈 목록입니다.")
	@GetMapping
	public ApiResponse<LetterListResponse> getMyLetters(@AuthenticationPrincipal Long memberId,
			@Parameter(description = "0부터") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "기본 20, 최대 50") @RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(letterService.getMyLetters(memberId, page, size));
	}

	@Operation(summary = "편지 보내기", description = "개발자에게 편지를 보냅니다. 대화방이 없으면 이때 만들어지고, 받은 편지는 함께 읽음 처리됩니다.")
	@PostMapping
	public ApiResponse<LetterMessageResponse> send(@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody LetterSendRequest request) {
		return ApiResponse.ok(letterService.send(memberId, request));
	}

	@Operation(summary = "편지 사진 Presigned URL 발급", description = "uploadUrl로 PUT한 뒤 awsKey를 편지의 imageKey로 넘깁니다.")
	@GetMapping("/images/presigned-url")
	public ApiResponse<PresignedUrlResponse> getImagePresignedUrl(@AuthenticationPrincipal Long memberId,
			@Parameter(description = "image/jpeg, image/png 등") @RequestParam String mimeType) {
		return ApiResponse.ok(letterService.generateImagePresignedUrl(memberId, mimeType));
	}

	@Operation(summary = "안 읽은 편지 개수", description = "벨 배지 표시용. 개발자가 보낸 편지 중 안 읽은 수입니다.")
	@GetMapping("/unread-count")
	public ApiResponse<LetterUnreadCountResponse> getUnreadCount(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(letterService.getUnreadCount(memberId));
	}

	@Operation(summary = "편지 읽음 처리", description = "편지함을 열 때 호출합니다. 받은 편지를 전부 읽음으로 만듭니다.")
	@PatchMapping("/read")
	public ApiResponse<Void> markAsRead(@AuthenticationPrincipal Long memberId) {
		letterService.markAsRead(memberId);
		return ApiResponse.ok(null);
	}
}
