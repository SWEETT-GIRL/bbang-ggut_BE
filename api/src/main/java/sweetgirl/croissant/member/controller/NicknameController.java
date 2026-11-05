package sweetgirl.croissant.member.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.member.dto.request.NicknameChangeRequest;
import sweetgirl.croissant.member.dto.response.MemberProfileResponse;
import sweetgirl.croissant.member.dto.response.NicknameAvailabilityResponse;
import sweetgirl.croissant.member.service.NicknameCommandService;
import sweetgirl.croissant.member.service.NicknameQueryService;

@Tag(name = "[Client] 닉네임", description = "닉네임 직접 바꾸기. 뽑기는 /users/me/nickname/draw")
@RestController
@RequestMapping("/users/me/nickname")
@RequiredArgsConstructor
public class NicknameController {

	private final NicknameCommandService nicknameCommandService;
	private final NicknameQueryService nicknameQueryService;

	@Operation(summary = "닉네임 바꾸기", description = "한글 · 영문 · 숫자 2~10자, 공백 없음. 형식 400 NICKNAME400_FORMAT · "
		+ "금칙어 400 NICKNAME400_FORBIDDEN · 중복 409 NICKNAME409_TAKEN · 14일 안 429 NICKNAME429_COOLDOWN · "
		+ "게스트 403 MEMBER403_GUEST · 작성 제한 · 정지 403 MEMBER403_WRITE_RESTRICTED · "
		+ "프로필 제한 403 MEMBER403_PROFILE_RESTRICTED. "
		+ "다시 바꿀 수 있는 날은 GET /users/me 의 nicknameChangeableAt.")
	@PatchMapping
	public ApiResponse<MemberProfileResponse> change(
			@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid NicknameChangeRequest request) {
		return ApiResponse.ok(nicknameCommandService.change(memberId, request.nickname()));
	}

	@Operation(summary = "닉네임 쓸 수 있는지", description = "입력 칸의 실시간 확인용. 안 되면 reason(FORMAT · FORBIDDEN · TAKEN), "
		+ "TAKEN 이면 빈 이름 후보 3개.")
	@GetMapping("/availability")
	public ApiResponse<NicknameAvailabilityResponse> checkAvailability(@RequestParam String nickname) {
		return ApiResponse.ok(nicknameQueryService.checkAvailability(nickname));
	}
}
