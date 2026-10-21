package sweetgirl.croissant.admin.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.response.AdminAutocompleteCacheEvictResponse;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.store.service.search.cache.AutocompleteCacheInvalidator;

/**
 * 자동완성 캐시 전체 비우기 — 앱 밖에서 바뀌어 이벤트로 잡을 수 없는 데이터용. 데이터 파이프라인이 store_name_alias 에 별칭을 넣었을 때,
 * Flyway 로 지역(search_region)을 바꾼 배포 뒤에 부른다. 가게 등록·수정은 자동으로 지워지므로 부를 필요가 없다.
 */
@RestController
@RequestMapping("/admin/search/autocomplete-cache")
@RequiredArgsConstructor
@Tag(name = "Admin Search", description = "검색 데이터 관리")
public class AdminAutocompleteCacheController {

	private final AutocompleteCacheInvalidator autocompleteCacheInvalidator;

	@DeleteMapping
	@Operation(summary = "자동완성 캐시 전체 비우기", description = "별칭 적재·지역 변경처럼 앱 밖에서 바뀐 데이터를 자동완성에 바로 반영합니다. "
		+ "비운 뒤 첫 검색들은 DB 를 조회해 다시 채웁니다.")
	public ApiResponse<AdminAutocompleteCacheEvictResponse> evictAll() {
		return ApiResponse.ok(new AdminAutocompleteCacheEvictResponse(autocompleteCacheInvalidator.evictAll()));
	}
}
