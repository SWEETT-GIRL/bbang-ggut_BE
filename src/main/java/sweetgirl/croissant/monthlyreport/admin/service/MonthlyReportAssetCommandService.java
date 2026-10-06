package sweetgirl.croissant.monthlyreport.admin.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAdminRequests.Asset;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAdminRequests.RemoveAsset;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAdminRequests.ReplaceAsset;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAssetResponse;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportAssetRepository;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportAssetReplacement;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportAssetReplacementRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class MonthlyReportAssetCommandService {
	private final MonthlyReportAssetRepository assets;
	private final MonthlyReportAssetQueryService assetQueryService;
	private final MonthlyReportAssetReplacementRepository replacements;

	public MonthlyReportAssetResponse registerReportAsset(Asset request) {
		assetQueryService.validateAssetRequest(request);
		String key = request.imageKey();
		rejectReplacedKey(key);
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

	public MonthlyReportAssetResponse replaceReportAsset(ReplaceAsset request) {
		Asset replacement = request.asset();
		assetQueryService.validateAssetRequest(replacement);
		if (request.applyToExistingReports() && !"image/webp".equals(replacement.contentType())) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_ADMIN_REQUEST);
		}
		String id = MonthlyReportAsset.identity(replacement.version(), replacement.kind(), replacement.characterCode(),
			replacement.planetCode(), replacement.variant());
		MonthlyReportAsset asset = assets.findForUpdate(id)
			.orElseThrow(() -> new CustomException(MonthlyReportErrorCode.ADMIN_CONFLICT));
		// 응답 유실 후 동일 요청을 재시도해도 이전 키의 연결을 다시 쓰지 않는다.
		if (asset.getImageKey().equals(replacement.imageKey())) {
			if (request.applyToExistingReports() && !replacements.findById(request.expectedImageKey())
				.map(previous -> previous.getImageKey().equals(replacement.imageKey())).orElse(false)) {
				throw new CustomException(MonthlyReportErrorCode.ADMIN_CONFLICT);
			}
			return MonthlyReportAssetResponse.from(asset);
		}
		if (!asset.getImageKey().equals(request.expectedImageKey())) {
			throw new CustomException(MonthlyReportErrorCode.ADMIN_CONFLICT);
		}
		rejectReplacedKey(replacement.imageKey());
		if (request.applyToExistingReports()) {
			replacements.forwardReplacements(asset.getImageKey(), replacement.imageKey());
			replacements.save(MonthlyReportAssetReplacement.replace(asset.getImageKey(), replacement.imageKey()));
		}
		asset.replaceImageKey(replacement.imageKey());
		return MonthlyReportAssetResponse.from(asset);
	}

	public void removeReportAsset(RemoveAsset request) {
		assets.findForUpdate(request.assetId()).ifPresent(asset -> {
			if (!asset.getImageKey().equals(request.expectedImageKey())) {
				throw new CustomException(MonthlyReportErrorCode.ADMIN_CONFLICT);
			}
			// S3 객체와 기존 보고서용 교체 연결은 보존한다.
			assets.delete(asset);
		});
	}

	private void rejectReplacedKey(String imageKey) {
		if (replacements.existsById(imageKey)) {
			throw new CustomException(MonthlyReportErrorCode.ADMIN_CONFLICT);
		}
	}
}
