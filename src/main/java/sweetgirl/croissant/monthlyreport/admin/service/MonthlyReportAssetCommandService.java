package sweetgirl.croissant.monthlyreport.admin.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAdminRequests.Asset;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAssetResponse;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportAssetRepository;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;

@Service
@RequiredArgsConstructor
@Transactional
public class MonthlyReportAssetCommandService {
	private final MonthlyReportAssetRepository assets;
	private final MonthlyReportAssetQueryService assetQueryService;

	public MonthlyReportAssetResponse registerReportAsset(Asset request) {
		assetQueryService.validateAssetRequest(request);
		String key = request.imageKey();
		String id = MonthlyReportAsset.identity(request.version(), request.kind(), request.characterCode(),
			request.planetCode(), request.variant());
		assets.register(id, request.version(), request.kind().name(), request.characterCode(), request.planetCode(),
			request.variant(), key);
		MonthlyReportAsset asset = assets.findById(id).orElseThrow();
		if (!asset.getImageKey().equals(key)) {
			throw new CustomException(MonthlyReportErrorCode.ADMIN_CONFLICT);
		}
		return MonthlyReportAssetResponse.from(asset);
	}
}
