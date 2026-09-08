package sweetgirl.croissant.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.response.AdminBreadTagReclassifyResponse;
import sweetgirl.croissant.admin.service.AdminBreadTagCommandService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Bread Tag", description = "어드민 빵로그 태그 관리 API")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminBreadTagController {

	private final AdminBreadTagCommandService adminBreadTagCommandService;

	@Operation(summary = "빵로그 태그 전체 재분류", description = "저장된 태그 전부를 현재 분류 규칙(끝단어 표)으로 다시 분류합니다. 이름은 그대로, 분류만 바뀝니다. "
		+ "분류 규칙(bread-record-categories.txt)을 고친 뒤 한 번 호출합니다.")
	@PostMapping("/bread-record-tags/reclassify")
	public ResponseEntity<ApiResponse<AdminBreadTagReclassifyResponse>> reclassifyAll() {
		return ResponseEntity.ok(ApiResponse.ok(adminBreadTagCommandService.reclassifyAll()));
	}
}
