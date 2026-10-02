package sweetgirl.croissant.legacy.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;
import sweetgirl.croissant.global.response.ApiResponse;

/**
 * 뱃지·미션은 지웠지만 1.5.x 앱이 부팅마다 아직 부른다 — 404 로 두면 한 달에 700건 넘게 실패 로그가 쌓이고 뱃지 선반이 오류로 보인다.
 * 앱이 더 이상 부르지 않을 때까지(1.6 전환 뒤) 빈 응답으로 받아 준다. 그때 이 클래스를 지운다.
 */
@Hidden
@RestController
@RequestMapping("/members/me")
public class LegacyBadgeMissionController {

	@GetMapping("/badges")
	public ResponseEntity<ApiResponse<List<Object>>> getBadges() {
		return ResponseEntity.ok(ApiResponse.ok(List.of()));
	}

	@PostMapping("/badges/{code}/celebrate")
	public ResponseEntity<ApiResponse<Void>> celebrate(@PathVariable String code) {
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@GetMapping("/missions")
	public ResponseEntity<ApiResponse<List<Object>>> getMissions() {
		return ResponseEntity.ok(ApiResponse.ok(List.of()));
	}
}
