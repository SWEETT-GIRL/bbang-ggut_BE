package sweetgirl.croissant.monthlyreport.admin.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;

@Service
@RequiredArgsConstructor
public class MonthlyReportAssetStore {
	private final S3Service storage;

	public Upload upload(String key, String type) {
		String url = storage.generateImmutableUploadUrl(key, type);
		return new Upload(key, url, Map.of("Content-Type", type, "If-None-Match", "*"));
	}

	public void verify(String key, String type) {
		HeadObjectResponse object = storage.headObject(key);
		if (!type.equals(object.contentType()) || object.contentLength() == null || object.contentLength() <= 0
			|| object.contentLength() > 20 * 1024 * 1024) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_ADMIN_REQUEST);
		}
	}

	public record Upload(String imageKey, String uploadUrl, Map<String, String> headers) {
	}
}
