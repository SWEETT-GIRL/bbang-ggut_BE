package sweetgirl.croissant.monthlyreport.admin.dto;

import java.util.Map;

public record MonthlyReportAssetUploadResponse(String imageKey, String uploadUrl, Map<String, String> headers) {
	public MonthlyReportAssetUploadResponse {
		headers = Map.copyOf(headers);
	}
}
