package sweetgirl.croissant.monthlyreport.admin.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAdminRequests.Asset;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAssetResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAssetUploadResponse;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset.Kind;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class MonthlyReportAssetUploadService {
	private final MonthlyReportAssetQueryService assetQueryService;
	private final MonthlyReportAssetCommandService assetCommandService;
	private final MonthlyReportAssetStore store;

	public MonthlyReportAssetUploadResponse issueAssetUploadUrl(Asset request) {
		assetQueryService.validateAssetRequest(request);
		String key = buildAssetKeyPrefix(request) + request.variant() + "-" + UUID.randomUUID()
			+ (request.contentType().equals("image/png") ? ".png" : ".webp");
		return store.issueAssetUploadUrl(key, request.contentType());
	}

	public MonthlyReportAssetResponse registerReportAsset(Asset request) {
		assetQueryService.validateAssetRequest(request);
		String key = request.imageKey();
		String extension = request.contentType().equals("image/png") ? ".png" : ".webp";
		if (key == null || !key.startsWith(buildAssetKeyPrefix(request)) || !key.endsWith(extension)
			|| !key.substring(buildAssetKeyPrefix(request).length())
				.matches(request.variant() + "(-[a-z0-9-]+)?\\.(png|webp)")) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_ADMIN_REQUEST);
		}
		store.validateStoredAsset(key, request.contentType());
		return assetCommandService.registerReportAsset(request);
	}

	private String buildAssetKeyPrefix(Asset request) {
		return "monthly-reports/assets/" + request.version() + "/"
			+ (request.kind() == Kind.COVER ? "covers/" : "types/") + request.characterCode() + "/"
			+ (request.kind() == Kind.TYPE ? request.planetCode() + "/" : "");
	}
}
