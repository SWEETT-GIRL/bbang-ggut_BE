package sweetgirl.croissant.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminMenuClassificationConfirmRequest;
import sweetgirl.croissant.admin.dto.response.AdminMenuClassificationConfirmResponse;
import sweetgirl.croissant.admin.service.AdminMenuClassificationCommandService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Menu Classification", description = "어드민 메뉴 AI 분류 검수 API")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminMenuClassificationController {

	private final AdminMenuClassificationCommandService adminMenuClassificationCommandService;

	@Operation(summary = "메뉴 이름 단위 분류 확정", description = "AI 가 3회 물어도 답이 갈려 미분류로 남은 이름에 kind 를 정합니다. "
		+ "캐시에 ADMIN 판정으로 남고, 같은 이름의 미분류 활성 메뉴에 바로 적용됩니다. 이미 분류된 메뉴는 건드리지 않습니다. "
		+ "검수 큐는 scripts/menu_review_queue.py 로 뽑습니다.")
	@PostMapping("/menu-classifications")
	public ResponseEntity<ApiResponse<AdminMenuClassificationConfirmResponse>> confirm(
			@Valid @RequestBody AdminMenuClassificationConfirmRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminMenuClassificationCommandService.confirm(request)));
	}
}
