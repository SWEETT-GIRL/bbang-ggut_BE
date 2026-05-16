package sweetgirl.croissant.banner.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.banner.code.BannerErrorCode;
import sweetgirl.croissant.banner.dto.response.BannerDetailResponse;
import sweetgirl.croissant.banner.dto.response.BannerSummaryResponse;
import sweetgirl.croissant.banner.exception.BannerException;
import sweetgirl.croissant.banner.repository.BannerRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BannerService {

	private final BannerRepository bannerRepository;

	public List<BannerSummaryResponse> getBanners() {
		return bannerRepository.findAllByOrderByDisplayOrderAsc().stream()
			.map(BannerSummaryResponse::from)
			.toList();
	}

	public BannerDetailResponse getBannerDetail(Long bannerId) {
		return BannerDetailResponse.from(
			bannerRepository.findById(bannerId)
				.orElseThrow(() -> new BannerException(BannerErrorCode.BANNER_NOT_FOUND)));
	}
}
