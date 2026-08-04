package sweetgirl.croissant.breadlog.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.breadlog.entity.StorePlan;

public record StorePlanResponse(
		Long storeId,
		String memo,
		LocalDateTime updatedAt) {

	public static StorePlanResponse from(StorePlan plan) {
		return new StorePlanResponse(
			plan.getStore().getId(),
			plan.getMemo(),
			plan.getUpdatedAt() != null ? plan.getUpdatedAt() : plan.getCreatedAt());
	}
}
