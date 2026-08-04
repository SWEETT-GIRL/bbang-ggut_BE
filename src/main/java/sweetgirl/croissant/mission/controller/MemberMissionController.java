package sweetgirl.croissant.mission.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.mission.dto.response.MemberMissionResponse;
import sweetgirl.croissant.mission.service.MemberMissionService;

@Tag(name = "Mission", description = "미션 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/members/me/missions")
public class MemberMissionController {

	private final MemberMissionService memberMissionService;

	@Operation(summary = "내 미션과 진행률 조회")
	@GetMapping
	public ResponseEntity<ApiResponse<List<MemberMissionResponse>>> getMissions(
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(memberMissionService.getMissions(memberId)));
	}
}
