package sweetgirl.croissant.banner.dto.response;

import sweetgirl.croissant.banner.entity.Banner;

public record BannerSummaryResponse(
		Long id,
		String thumbnailKey,
		int displayOrder) {

	public static BannerSummaryResponse from(Banner banner) {
		return new BannerSummaryResponse(banner.getId(), banner.getThumbnailKey(), banner.getDisplayOrder());
	}
}
