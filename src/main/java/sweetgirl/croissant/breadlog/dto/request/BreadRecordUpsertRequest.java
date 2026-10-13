package sweetgirl.croissant.breadlog.dto.request;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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
		/** 빵탑에 쌓을 빵. 빵탑을 모르는 구버전 앱은 안 보낸다. */
		@Size(max = 20) String towerBread,
		@NotNull LocalDateTime eatenAt,
		/** 사진은 선택이다. 없으면 빈 배열을 보낸다 — 가게만 고른 기록이 된다. */
		@NotNull @Size(max = 30) List<@Valid BreadRecordPhotoRequest> photos,
		@NotNull List<@Valid BreadRecordTagRequest> tags,
		/** 공개 여부. 생성 때 없으면 비공개, 수정 때 없으면(구버전 앱) 지금 값을 그대로 둔다 */
		Boolean isPublic,
		/**
		 * 이 저장 요청의 번호(선택). 응답을 못 받아 다시 보낼 때 같은 값을 쓰면 기록이 둘 생기지 않는다.
		 * 안 보내는 옛 앱은 지금처럼 요청마다 새 기록이 생긴다. 수정 요청에서는 보지 않는다.
		 */
		UUID requestId) {

	/** 요청 번호를 모르는 쪽(옛 앱의 모양)에서 만든다. */
	public BreadRecordUpsertRequest(
			Long storeId,
			String storeName,
			String regionKey,
			String memo,
			String lovedBread,
			List<String> lovedBreads,
			String towerBread,
			LocalDateTime eatenAt,
			List<BreadRecordPhotoRequest> photos,
			List<BreadRecordTagRequest> tags,
			Boolean isPublic) {
		this(storeId, storeName, regionKey, memo, lovedBread, lovedBreads, towerBread, eatenAt, photos, tags,
			isPublic, null);
	}
}
