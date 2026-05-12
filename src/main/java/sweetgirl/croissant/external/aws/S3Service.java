package sweetgirl.croissant.external.aws;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.external.aws.exception.S3ErrorCode;
import sweetgirl.croissant.external.aws.exception.S3StorageException;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aws.credentials.access-key")
public class S3Service {

	private final S3Presigner s3Presigner;

	@Value("${aws.s3.bucket}")
	private String bucketName;

	public PresignedUrlResponse generatePresignedUrl(String path, String mimeTypeStr) {
		MimeType mimeType = MimeType.fromString(mimeTypeStr);
		String awsKey = path + "/" + UUID.randomUUID();

		try {
			PutObjectRequest objectRequest = PutObjectRequest.builder()
				.bucket(bucketName)
				.key(awsKey)
				.contentType(mimeType.getValue())
				.build();

			PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
				.signatureDuration(Duration.ofMinutes(10))
				.putObjectRequest(objectRequest)
				.build();

			String url = s3Presigner.presignPutObject(presignRequest).url().toString();
			return new PresignedUrlResponse(url, awsKey);

		} catch (S3Exception e) {
			throw new S3StorageException(S3ErrorCode.PRE_SIGNED_URL_FAILED, e.awsErrorDetails().errorMessage());
		}
	}
}
