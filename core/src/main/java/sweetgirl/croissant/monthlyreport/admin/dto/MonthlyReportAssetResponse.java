package sweetgirl.croissant.monthlyreport.admin.dto;

import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset;

public record MonthlyReportAssetResponse(String id, String assetVersion, MonthlyReportAsset.Kind kind,
		String characterCode, String planetCode, String variant, String imageKey) {
	public static MonthlyReportAssetResponse from(MonthlyReportAsset asset) {
		return new MonthlyReportAssetResponse(asset.getId(), asset.getAssetVersion(), asset.getKind(),
			asset.getCharacterCode(), asset.getPlanetCode(), asset.getVariant(), asset.getImageKey());
	}
}
