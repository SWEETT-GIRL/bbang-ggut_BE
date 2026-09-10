package sweetgirl.croissant.breadlog.dto.request;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BreadRecordUpsertRequest(
		Long storeId,
		@NotBlank @Size(max = 100) String storeName,
		@Size(max = 100) String regionKey,
		@Size(max = 500) String memo,
		/** 구버전 앱이 보내는 단일 값. lovedBreads가 오면 무시한다. */
		@Size(max = 100) String lovedBread,
		/** 최고였던 빵들 — 각각 tags 중 하나여야 한다 */
		@Size(max = 10) List<@Size(max = 100) String> lovedBreads,
		@NotNull LocalDateTime eatenAt,
		@NotNull @Size(min = 1, max = 30) List<@Valid BreadRecordPhotoRequest> photos,
		@NotNull List<@Valid BreadRecordTagRequest> tags) {
}
