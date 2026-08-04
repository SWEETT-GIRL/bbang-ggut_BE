package sweetgirl.croissant.badge.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.badge.dto.response.MemberBadgeResponse;
import sweetgirl.croissant.badge.service.BadgeService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Badge", description = "뱃지 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/members/me/badges")
public class MemberBadgeController {

	private final BadgeService badgeService;

	@Operation(summary = "내 뱃지 목록 및 달성 여부 조회")
	@GetMapping
	public ResponseEntity<ApiResponse<List<MemberBadgeResponse>>> getBadges(
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(badgeService.getBadges(memberId)));
	}

	@Operation(summary = "뱃지 획득 축하 확인")
	@PostMapping("/{code}/celebrate")
	public ResponseEntity<ApiResponse<Void>> celebrate(
			@AuthenticationPrincipal Long memberId,
			@PathVariable String code) {
		badgeService.celebrate(memberId, code);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}
