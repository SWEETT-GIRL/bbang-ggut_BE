package sweetgirl.croissant.monthlyreport.admin.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset.Kind;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportGenerationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportGenerationService.Prepared;

@Service
@RequiredArgsConstructor
public class MonthlyReportPreparation {
	private final MonthlyReportAppearanceReader appearance;
	private final MonthlyReportGenerationService generation;
	private final MonthlyReportAssetQueryService assets;
	private final ObjectMapper mapper;

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Prepared prepare(Long memberId, String month, String assetVersion) {
		Prepared prepared = generation.prepare(appearance.createAppearanceDraft(memberId, month),
			mapper.createObjectNode().put("assemblyVersion", "admin-report-v1"));
		MonthlyReportResponse report = prepared.report();
		String character = report.character().code();
		String planet = report.planet().code();
		String type = report.activity().type();
		String cover = assets.getRequiredAssetImageKey(assetVersion, Kind.COVER, character, "", month.substring(5));
		String artwork = assets.getRequiredAssetImageKey(assetVersion, Kind.TYPE, character, planet, type);
		MonthlyReportResponse.Activity activity = new MonthlyReportResponse.Activity(type,
			report.activity().totalScore(),
			report.activity().abilities(),
			new MonthlyReportResponse.Artwork(character, planet, type, assetVersion, artwork));
		prepared.metrics().putObject("appearance").put("assetVersion", assetVersion)
			.put("capturedAt", prepared.generatedAt().toString()).put("source", "preparation-time-world");
		MonthlyReportResponse result = new MonthlyReportResponse(report.schemaVersion(), month, memberId,
			report.nickname(), report.character(), report.planet(), report.summary(), report.stores(), report.breads(),
			activity, report.comparison(), report.towns(), cover);
		return new Prepared(result, prepared.metrics(), prepared.generatedAt());
	}
}
