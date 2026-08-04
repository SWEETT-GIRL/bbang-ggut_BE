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
		@Size(max = 100) String lovedBread,
		@NotNull LocalDateTime eatenAt,
		@NotNull @Size(max = 10) List<@Valid BreadRecordPhotoRequest> photos,
		@NotNull List<@Valid BreadRecordTagRequest> tags) {
}
