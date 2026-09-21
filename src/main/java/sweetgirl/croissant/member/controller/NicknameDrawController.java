package sweetgirl.croissant.member.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.member.dto.response.MemberProfileResponse;
import sweetgirl.croissant.member.dto.response.NicknameDrawResponse;
import sweetgirl.croissant.member.dto.response.NicknameDrawStatusResponse;
import sweetgirl.croissant.member.service.NicknameDrawService;

@Tag(name = "Nickname", description = "닉네임 뽑기 API")
@RestController
@RequestMapping("/users/me/nickname/draw")
@RequiredArgsConstructor
public class NicknameDrawController {

	private final NicknameDrawService nicknameDrawService;

	@Operation(summary = "닉네임 뽑기 상태", description = "오늘 뽑을 수 있는지, 아직 처리 안 한 후보가 있는지 반환합니다. "
		+ "광고를 보여주기 전에 먼저 호출하세요.")
	@GetMapping
	public ApiResponse<NicknameDrawStatusResponse> getStatus(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(nicknameDrawService.getStatus(memberId));
	}

	@Operation(summary = "닉네임 뽑기", description = "새 닉네임 후보를 랜덤으로 하나 뽑습니다. 한국 날짜로 하루 한 번이며, "
		+ "이미 뽑았으면 429(NICKNAME429_DRAW_LIMIT). 닉네임은 수락해야 바뀝니다.")
	@PostMapping
	public ApiResponse<NicknameDrawResponse> draw(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(nicknameDrawService.draw(memberId));
	}

	@Operation(summary = "뽑은 닉네임으로 바꾸기", description = "후보가 없으면 400(NICKNAME400_NO_PENDING), "
		+ "그새 다른 사람이 같은 이름을 가졌으면 409(NICKNAME409_TAKEN) — 이때는 원래대로를 호출하세요.")
	@PostMapping("/accept")
	public ApiResponse<MemberProfileResponse> accept(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(nicknameDrawService.accept(memberId));
	}

	@Operation(summary = "원래대로", description = "뽑은 후보를 버리고 기존 닉네임을 유지합니다. 오늘 뽑기 기회는 돌아오지 않습니다.")
	@PostMapping("/discard")
	public ApiResponse<MemberProfileResponse> discard(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(nicknameDrawService.discard(memberId));
	}
}
