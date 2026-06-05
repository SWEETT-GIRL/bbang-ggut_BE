package sweetgirl.croissant.banner.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.banner.code.BannerErrorCode;
import sweetgirl.croissant.banner.dto.request.BannerCreateRequest;
import sweetgirl.croissant.banner.dto.request.BannerReorderRequest;
import sweetgirl.croissant.banner.entity.Banner;
import sweetgirl.croissant.banner.exception.BannerException;
import sweetgirl.croissant.banner.repository.BannerRepository;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.external.aws.util.S3PathGenerator;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminBannerService {

	private final BannerRepository bannerRepository;
	private final S3Service s3Service;

	public PresignedUrlResponse getPresignedUrl(String mimeType) {
		return s3Service.generatePresignedUrl(S3PathGenerator.getBannerPath(), mimeType);
	}

	@Transactional
	public void createBanner(BannerCreateRequest request) {
		int nextOrder = (int)bannerRepository.count();
		Banner banner = Banner.builder()
			.thumbnailName(request.getThumbnailName())
			.thumbnailMimeType(MimeType.fromString(request.getThumbnailMimeType()))
			.thumbnailKey(request.getThumbnailKey())
			.detailName(request.getDetailName())
			.detailMimeType(MimeType.fromString(request.getDetailMimeType()))
			.detailKey(request.getDetailKey())
			.displayOrder(nextOrder)
			.build();
		bannerRepository.save(banner);
	}

	@Transactional
	public void deleteBanner(Long bannerId) {
		Banner banner = bannerRepository.findById(bannerId)
			.orElseThrow(() -> new BannerException(BannerErrorCode.BANNER_NOT_FOUND));
		s3Service.deleteObject(banner.getThumbnailKey());
		s3Service.deleteObject(banner.getDetailKey());
		bannerRepository.delete(banner);
	}

	@Transactional
	public void reorderBanners(BannerReorderRequest request) {
		List<Long> bannerIds = request.getBannerIds();
		Map<Long, Banner> bannerMap = bannerRepository.findAllById(bannerIds).stream()
			.collect(Collectors.toMap(Banner::getId, b -> b));

		IntStream.range(0, bannerIds.size()).forEach(i -> {
			Banner banner = bannerMap.get(bannerIds.get(i));
			if (banner == null) {
				throw new BannerException(BannerErrorCode.BANNER_NOT_FOUND);
			}
			banner.updateDisplayOrder(i);
		});
	}
}
