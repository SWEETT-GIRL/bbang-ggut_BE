package sweetgirl.croissant.admin.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.service.AdminTutorialTemplateCommandService;
import sweetgirl.croissant.breadlog.dto.TutorialTemplateContent;
import sweetgirl.croissant.breadlog.dto.response.TutorialTemplateResponse;
import sweetgirl.croissant.breadlog.service.TutorialTemplateQueryService;
import sweetgirl.croissant.global.response.ApiResponse;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/tutorial-templates")
public class AdminTutorialTemplateController {
	private final AdminTutorialTemplateCommandService commands;
	private final TutorialTemplateQueryService queries;

	public record UploadRequest(@NotBlank String contentType) {
	}
	public record ActivationRequest(String expectedActiveCode) {
	}
	public record CatalogResponse(String activeCode, List<TutorialTemplateResponse> revisions) {
	}

	@GetMapping
	public ApiResponse<CatalogResponse> list() {
		return ApiResponse.ok(new CatalogResponse(queries.active().map(TutorialTemplateResponse::code).orElse(null),
			queries.list()));
	}

	@PostMapping("/upload-url")
	public ApiResponse<AdminTutorialTemplateCommandService.Upload> upload(@Valid @RequestBody UploadRequest request) {
		return ApiResponse.ok(commands.upload(request.contentType()));
	}

	@PostMapping
	public ApiResponse<Map<String, String>> save(@Valid @RequestBody TutorialTemplateContent content) {
		return ApiResponse.created(Map.of("code", commands.saveDraft(content)));
	}

	@PostMapping("/{code}/publish")
	public ApiResponse<Void> publish(@PathVariable String code, @RequestBody ActivationRequest request) {
		commands.publish(code, request.expectedActiveCode());
		return ApiResponse.ok(null);
	}

	@PostMapping("/stop")
	public ApiResponse<Void> stop(@RequestBody ActivationRequest request) {
		commands.stop(request.expectedActiveCode());
		return ApiResponse.ok(null);
	}
}
