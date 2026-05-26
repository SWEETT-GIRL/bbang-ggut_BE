package sweetgirl.croissant.external.aws;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
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
	private final S3Client s3Client;

	@Value("${aws.s3.bucket}")
	private String bucketName;

	public PresignedUrlResponse generatePresignedUrl(String path, String mimeTypeStr) {
		MimeType mimeType = MimeType.fromString(mimeTypeStr);
		String fileName = UUID.randomUUID().toString();
		String awsKey = path + "/" + fileName;

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
			return new PresignedUrlResponse(fileName, mimeTypeStr, url, awsKey);

		} catch (S3Exception e) {
			throw new S3StorageException(S3ErrorCode.PRE_SIGNED_URL_FAILED, e.awsErrorDetails().errorMessage());
		}
	}

	public void deleteObject(String awsKey) {
		try {
			DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
				.bucket(bucketName)
				.key(awsKey)
				.build();
			s3Client.deleteObject(deleteRequest);
		} catch (S3Exception e) {
			throw new S3StorageException(S3ErrorCode.DELETE_FAILED, e.awsErrorDetails().errorMessage());
		}
	}

	// reports/ 영역의 객체를 stores/, menus/ 등 최종 경로로 server-side copy
	// 동일 버킷·리전이라 네트워크 전송 비용 없음, PUT 1회 요금만 발생
	public void copyObject(String sourceKey, String destinationKey) {
		try {
			CopyObjectRequest copyRequest = CopyObjectRequest.builder()
				.sourceBucket(bucketName)
				.sourceKey(sourceKey)
				.destinationBucket(bucketName)
				.destinationKey(destinationKey)
				.build();
			s3Client.copyObject(copyRequest);
		} catch (S3Exception e) {
			throw new S3StorageException(S3ErrorCode.COPY_FAILED, e.awsErrorDetails().errorMessage());
		}
	}
}
