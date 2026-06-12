package sweetgirl.croissant.admin.controller;

import java.util.concurrent.RejectedExecutionException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.opensearch.batch.StoreDocumentIndexer;
import sweetgirl.croissant.external.opensearch.exception.OpenSearchErrorCode;
import sweetgirl.croissant.external.opensearch.exception.OpenSearchException;
import sweetgirl.croissant.global.response.ApiResponse;

@RestController
@RequestMapping("/admin/search")
@ConditionalOnProperty(name = "opensearch.endpoint")
@RequiredArgsConstructor
@Tag(name = "Admin Search", description = "OpenSearch 인덱스 관리")
public class AdminSearchController {

	private final StoreDocumentIndexer storeDocumentIndexer;

	@PostMapping("/reindex/stores")
	@Operation(summary = "가게 전체 재색인")
	public ApiResponse<String> reindexStores() {
		try {
			storeDocumentIndexer.reindexAllStores();
		} catch (RejectedExecutionException e) {
			throw new OpenSearchException(OpenSearchErrorCode.REINDEX_ALREADY_IN_PROGRESS);
		}
		return ApiResponse.ok("재색인 작업이 시작되었습니다.");
	}
}
