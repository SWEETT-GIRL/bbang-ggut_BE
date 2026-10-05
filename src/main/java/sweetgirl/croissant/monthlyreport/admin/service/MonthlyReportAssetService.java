package sweetgirl.croissant.monthlyreport.admin.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAdminRequests.Asset;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset.Kind;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportAssetRepository;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.repository.WorldPlanetRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportAssetService {
	private final MonthlyReportAssetRepository assets;
	private final WorldCharacterRepository characters;
	private final WorldPlanetRepository planets;
	private final MonthlyReportAssetStore store;

	public MonthlyReportAssetStore.Upload issueAssetUploadUrl(Asset request) {
		validateAssetRequest(request);
		String key = buildAssetKeyPrefix(request) + request.variant() + "-" + UUID.randomUUID()
			+ (request.contentType().equals("image/png") ? ".png" : ".webp");
		return store.issueAssetUploadUrl(key, request.contentType());
	}

	@Transactional
	public MonthlyReportAsset registerReportAsset(Asset request) {
		validateAssetRequest(request);
		String key = request.imageKey();
		String extension = request.contentType().equals("image/png") ? ".png" : ".webp";
		if (key == null || !key.startsWith(buildAssetKeyPrefix(request)) || !key.endsWith(extension)
			|| !key.substring(buildAssetKeyPrefix(request).length())
				.matches(request.variant() + "(-[a-z0-9-]+)?\\.(png|webp)")) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_ADMIN_REQUEST);
		}
		store.validateStoredAsset(key, request.contentType());
		String id = MonthlyReportAsset.identity(request.version(), request.kind(), request.characterCode(),
			request.planetCode(), request.variant());
		assets.register(id, request.version(), request.kind().name(), request.characterCode(), request.planetCode(),
			request.variant(), key);
		MonthlyReportAsset asset = assets.findById(id).orElseThrow();
		if (!asset.getImageKey().equals(key)) {
			throw new CustomException(MonthlyReportErrorCode.ADMIN_CONFLICT);
		}
		return asset;
	}

	public List<MonthlyReportAsset> getReportAssets(String version) {
		return assets.findByAssetVersionOrderById(version);
	}

	public String getRequiredAssetImageKey(String version, Kind kind, String character, String planet, String variant) {
		return assets.findById(MonthlyReportAsset.identity(version, kind, character, planet, variant))
			.map(MonthlyReportAsset::getImageKey)
			.orElseThrow(() -> new CustomException(MonthlyReportErrorCode.ASSET_MISSING));
	}

	private String buildAssetKeyPrefix(Asset request) {
		return "monthly-reports/assets/" + request.version() + "/"
			+ (request.kind() == Kind.COVER ? "covers/" : "types/") + request.characterCode() + "/"
			+ (request.kind() == Kind.TYPE ? request.planetCode() + "/" : "");
	}

	private void validateAssetRequest(Asset request) {
		boolean valid = request.kind() == Kind.COVER
			? request.planetCode().isEmpty() && request.variant().matches("0[1-9]|1[0-2]")
			: planets.existsById(request.planetCode())
				&& Set.of("explore", "save", "plan", "record", "seed").contains(request.variant());
		if (!valid || !characters.existsById(request.characterCode())) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_ADMIN_REQUEST);
		}
	}
}
