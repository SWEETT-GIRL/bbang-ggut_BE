package sweetgirl.croissant.monthlyreport.admin.service;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAdminRequests.Asset;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAssetResponse;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset.Kind;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportAssetRepository;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.repository.WorldPlanetRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportAssetQueryService {
	private final MonthlyReportAssetRepository assets;
	private final WorldCharacterRepository characters;
	private final WorldPlanetRepository planets;

	public List<MonthlyReportAssetResponse> getReportAssets(String version) {
		return assets.findByAssetVersionOrderById(version).stream().map(MonthlyReportAssetResponse::from).toList();
	}

	public String getRequiredAssetImageKey(String version, Kind kind, String character, String planet, String variant) {
		return assets.findById(MonthlyReportAsset.identity(version, kind, character, planet, variant))
			.map(MonthlyReportAsset::getImageKey)
			.orElseThrow(() -> new CustomException(MonthlyReportErrorCode.ASSET_MISSING));
	}

	public void validateAssetRequest(Asset request) {
		boolean valid = switch (request.kind()) {
			case COVER -> request.planetCode().isEmpty() && request.variant().matches("0[1-9]|1[0-2]");
			case FACE -> request.planetCode().isEmpty()
				&& Set.of("smile", "wink", "neutral", "closed").contains(request.variant());
			case TYPE -> planets.existsById(request.planetCode())
				&& Set.of("explore", "save", "plan", "record", "seed").contains(request.variant());
		};
		if (!valid || !characters.existsById(request.characterCode())) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_ADMIN_REQUEST);
		}
	}
}
