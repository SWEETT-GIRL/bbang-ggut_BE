package sweetgirl.croissant.reward.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.reward.dto.request.InviteAcceptRequest;
import sweetgirl.croissant.reward.dto.response.InviteAcceptResponse;
import sweetgirl.croissant.reward.dto.response.InviteResponse;
import sweetgirl.croissant.reward.service.InviteService;

@Tag(name = "[Client] 친구 초대", description = "초대 코드 · 초대 현황")
@RestController
@RequiredArgsConstructor
@RequestMapping("/members/me/invite")
public class InviteController {

	private final InviteService inviteService;

	@Operation(summary = "내 초대 현황", description = "내 초대 코드와 기다리는 친구 · 성사 수를 내린다. 코드가 없으면 이때 만든다.")
	@GetMapping
	public ResponseEntity<ApiResponse<InviteResponse>> getMyInvite(@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(inviteService.getMyInvite(memberId)));
	}

	@Operation(summary = "초대 코드 넣기", description = "가입한 지 7 일 안, 아직 빵을 기록하기 전에 한 번만 넣을 수 있다. "
		+ "코드를 넣고 30 일 안에 첫 빵을 기록하면 둘 다 부스러기를 받는다.")
	@PostMapping("/accept")
	public ResponseEntity<ApiResponse<InviteAcceptResponse>> accept(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody InviteAcceptRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(inviteService.accept(memberId, request.code())));
	}
}
