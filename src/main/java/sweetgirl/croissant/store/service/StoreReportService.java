package sweetgirl.croissant.store.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.external.aws.util.S3PathGenerator;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.code.StoreReportErrorCode;
import sweetgirl.croissant.store.dto.request.ReportPresignedUrlRequest;
import sweetgirl.croissant.store.dto.response.PresignedUrlInfo;
import sweetgirl.croissant.store.dto.response.PresignedUrlInfoResponse;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.exception.StoreReportException;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
public class StoreReportService {

	private final S3Service s3Service;
	private final StoreRepository storeRepository;
	private final MenuRepository menuRepository;

	// mimeType 개수만큼 S3에 PresignedUrl 발급 요청
	public PresignedUrlInfoResponse generateStoreReportPresignedUrls(Long storeId,
			ReportPresignedUrlRequest request) {

		storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		if (request.mimeTypes().size() > 5) { // 최대 5장 제한
			throw new StoreReportException(StoreReportErrorCode.REPORT_IMAGE_COUNT_EXCEEDED);
		}

		List<PresignedUrlInfo> urls = request.mimeTypes().stream()
			.map(mimeType -> {
				String path = S3PathGenerator.getReportStoreImagePath(storeId); // storeId 기반 S3 저장 경로 생성
				PresignedUrlResponse response = s3Service.generatePresignedUrl(path, mimeType); // presigned URL 발급 요청
				return new PresignedUrlInfo(response.uploadUrl(), response.awsKey());
			}).toList();

		return new PresignedUrlInfoResponse(urls);
	}

	// mimeType 개수만큼 S3에 PresignedUrl 발급 요청
	public PresignedUrlInfoResponse generateMenuReportPresignedUrls(Long menuId,
			ReportPresignedUrlRequest request) {

		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		if (request.mimeTypes().size() > 5) { // 최대 5장 제한
			throw new StoreReportException(StoreReportErrorCode.REPORT_IMAGE_COUNT_EXCEEDED);
		}

		List<PresignedUrlInfo> urls = request.mimeTypes().stream()
			.map(mimeType -> {
				Long storeId = menu.getStore().getId();
				String path = S3PathGenerator.getReportMenuPath(storeId, menuId);
				PresignedUrlResponse response = s3Service.generatePresignedUrl(path, mimeType); // presigned URL 발급 요청
				return new PresignedUrlInfo(response.uploadUrl(), response.awsKey());
			}).toList();

		return new PresignedUrlInfoResponse(urls);
	}

}
