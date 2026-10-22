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
		/** 최고였던 빵들 — 각각 tags 중 하나여야 한다. 개수는 tags 를 넘을 수 없어 따로 막지 않는다. */
		List<@Size(max = 100) String> lovedBreads,
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
		UUID requestId,
		/** "이 집 또 갈래요". 생성 때 없으면 null(미정), 수정 때 없으면(구버전 앱) 지금 값을 그대로 둔다 */
		Boolean revisit,
		/**
		 * 바로 앞에 저장한 기록 번호(선택). 기록 완료 화면의 "연속 기록하고 2배 받기"로 이어 쓴 기록이면 앱이 싣는다 —
		 * 그 기록이 내 것이고 방금(30분 안) 저장됐으면 이번 기록 보상을 한 번 더 준다. 생성에서만 보고 수정에서는 보지 않는다.
		 */
		Long chainFromRecordId,
		/**
		 * 같이 간 사람(회원 id, 최대 5). 팔로우 관계(팔로잉 · 팔로워)이거나 빵투어 일행인 사람만. 안 보내면(null, 구버전 앱)
		 * 지금 태그를 그대로 두고, 빈 배열이면 전부 뺀다. 태그된 사람이 스스로 뺀 사람은 다시 넣을 수 없다(400).
		 */
		@Size(max = 5) List<Long> companionMemberIds) {

	/** 같이 간 사람을 모르는 쪽에서 만든다. */
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
			Boolean isPublic,
			UUID requestId,
			Boolean revisit,
			Long chainFromRecordId) {
		this(storeId, storeName, regionKey, memo, lovedBread, lovedBreads, towerBread, eatenAt, photos, tags,
			isPublic, requestId, revisit, chainFromRecordId, null);
	}

	/** 연속 기록을 모르는 쪽에서 만든다. */
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
			Boolean isPublic,
			UUID requestId,
			Boolean revisit) {
		this(storeId, storeName, regionKey, memo, lovedBread, lovedBreads, towerBread, eatenAt, photos, tags,
			isPublic, requestId, revisit, null);
	}

	/** 또 갈래요를 모르는 쪽에서 만든다. */
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
			Boolean isPublic,
			UUID requestId) {
		this(storeId, storeName, regionKey, memo, lovedBread, lovedBreads, towerBread, eatenAt, photos, tags,
			isPublic, requestId, null);
	}

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
