package sweetgirl.croissant.external.aws.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.aws.dto.ImageStatusUpdateRequest;
import sweetgirl.croissant.external.aws.service.ImageCallbackService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Callback", description = "Lambda 콜백 수신 API")
@RestController
@RequestMapping("/callback")
@RequiredArgsConstructor
public class ImageCallbackController {

	@Value("${lambda.callback.secret-key}")
	private String secretKey;

	private final ImageCallbackService imageCallbackService;

	@Operation(summary = "이미지 상태 업데이트 (Lambda 콜백)")
	@PatchMapping("/images/status")
	public ApiResponse<Void> updateImageStatus(
			@RequestHeader("X-Callback-Key") String key,
			@Valid @RequestBody ImageStatusUpdateRequest request) {

		if (!secretKey.equals(key)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}

		imageCallbackService.updateImageStatus(request);
		return ApiResponse.ok(null);
	}
}
