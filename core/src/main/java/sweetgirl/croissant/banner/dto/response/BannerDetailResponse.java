package sweetgirl.croissant.banner.dto.response;

import sweetgirl.croissant.banner.entity.Banner;

public record BannerDetailResponse(
		Long id,
		String detailKey) {

	public static BannerDetailResponse from(Banner banner) {
		return new BannerDetailResponse(banner.getId(), banner.getDetailKey());
	}
}
