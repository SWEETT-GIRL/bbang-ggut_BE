package sweetgirl.croissant.world.controller;

import java.util.Arrays;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.world.dto.response.WorldCatalogResponse;
import sweetgirl.croissant.world.service.WorldCatalogService;

@Tag(name = "[Client] 빵별일지 세계관", description = "행성 · 고도 단계 · 캐릭터 카탈로그")
@RestController
@RequiredArgsConstructor
@RequestMapping("/world")
public class WorldCatalogController {

	private final WorldCatalogService worldCatalogService;

	@Operation(summary = "세계관 카탈로그 조회", description = "회원과 무관한 전역 카탈로그. 응답에 실린 ETag 를 다음 요청의 If-None-Match 로 "
		+ "보내면, 카탈로그가 그대로일 때 304 로 답해 본문을 보내지 않는다.")
	@GetMapping("/catalog")
	public ResponseEntity<ApiResponse<WorldCatalogResponse>> getCatalog(
			@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
		WorldCatalogResponse catalog = worldCatalogService.getCatalog();
		String eTag = "W/\"" + catalog.version() + "\"";

		if (matches(ifNoneMatch, eTag)) {
			return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(eTag).build();
		}
		return ResponseEntity.ok().eTag(eTag).body(ApiResponse.ok(catalog));
	}

	/** If-None-Match 는 값을 여럿 실을 수 있고 {@code *} 는 "무엇이든 있으면"을 뜻한다. */
	private boolean matches(String ifNoneMatch, String eTag) {
		if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
			return false;
		}
		return Arrays.stream(ifNoneMatch.split(","))
			.map(String::trim)
			.anyMatch(candidate -> candidate.equals("*") || candidate.equals(eTag));
	}
}
