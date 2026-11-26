package sweetgirl.croissant.external.aws;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.external.aws.dto.S3ObjectSummary;
import sweetgirl.croissant.external.aws.exception.S3ErrorCode;
import sweetgirl.croissant.external.aws.exception.S3StorageException;

@Service
@Profile({"local", "staging", "prod"})
@RequiredArgsConstructor
// 이미지 업로드(presigned URL 발급 등)가 사용자 요청 경로에 있어 S3 지연을 별도로 본다.
@Timed(value = "aws.s3", histogram = true)
public class S3Service {

	private final S3Presigner s3Presigner;
	private final S3Client s3Client;

	@Value("${storage.bucket}")
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

	/**
	 * 키를 호출하는 쪽이 정하는 presign. 파일명이 뜻을 가져야 하는 자리에 쓴다 —
	 * 어드민이 목록에서 무슨 그림인지 읽을 수 있어야 하는 세계관 에셋 같은 것.
	 *
	 * <p>키는 서버가 조립한 값만 넘긴다. 사용자가 보낸 문자열을 그대로 키로 쓰면 버킷 아무 데나
	 * 쓸 수 있게 된다.
	 */
	public PresignedUrlResponse generatePresignedUrlForKey(String awsKey, String mimeTypeStr) {
		MimeType mimeType = MimeType.fromString(mimeTypeStr);
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
			return new PresignedUrlResponse(awsKey.substring(awsKey.lastIndexOf('/') + 1),
				mimeTypeStr, url, awsKey);

		} catch (S3Exception e) {
			throw new S3StorageException(S3ErrorCode.PRE_SIGNED_URL_FAILED, e.awsErrorDetails().errorMessage());
		}
	}

	/** 버전이 고정된 에셋의 기존 객체를 덮어쓰지 않는 업로드 URL. 반환 헤더를 함께 전송해야 한다. */
	public String generateImmutableUploadUrl(String key, String contentType) {
		PutObjectRequest put = PutObjectRequest.builder().bucket(bucketName).key(key).contentType(contentType)
			.ifNoneMatch("*").build();
		return s3Presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(10))
			.putObjectRequest(put).build()).url().toString();
	}

	public HeadObjectResponse headObject(String key) {
		return s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(key).build());
	}

	public void putObject(String awsKey, byte[] bytes, MimeType mimeType) {
		try {
			PutObjectRequest objectRequest = PutObjectRequest.builder()
				.bucket(bucketName)
				.key(awsKey)
				.contentType(mimeType.getValue())
				.contentLength((long)bytes.length)
				.build();
			s3Client.putObject(objectRequest, RequestBody.fromBytes(bytes));
		} catch (S3Exception e) {
			throw new S3StorageException(S3ErrorCode.UPLOAD_FAILED, e.awsErrorDetails().errorMessage());
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

	public List<String> listAllKeys(String prefix) {
		List<String> keys = new ArrayList<>();
		String continuationToken = null;
		try {
			do {
				ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
					.bucket(bucketName)
					.prefix(prefix);
				if (continuationToken != null) {
					requestBuilder.continuationToken(continuationToken);
				}
				ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
				response.contents().forEach(s3Object -> keys.add(s3Object.key()));
				continuationToken = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken()
					: null;
			} while (continuationToken != null);
		} catch (S3Exception e) {
			throw new S3StorageException(S3ErrorCode.LIST_FAILED, e.awsErrorDetails().errorMessage());
		}
		return keys;
	}

	/**
	 * 접두사 아래 객체를 페이지(최대 1000개)마다 읽어 하나씩 넘긴다.
	 *
	 * <p>키가 수백만 개인 접두사도 목록 전체를 메모리에 올리지 않는다. 용량·최종 수정 시각이 함께 온다.
	 */
	public void forEachObject(String prefix, Consumer<S3ObjectSummary> consumer) {
		String continuationToken = null;
		try {
			do {
				ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
					.bucket(bucketName)
					.prefix(prefix);
				if (continuationToken != null) {
					requestBuilder.continuationToken(continuationToken);
				}
				ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
				response.contents().forEach(s3Object -> consumer.accept(
					new S3ObjectSummary(s3Object.key(), s3Object.size(), s3Object.lastModified())));
				continuationToken = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken()
					: null;
			} while (continuationToken != null);
		} catch (S3Exception e) {
			throw new S3StorageException(S3ErrorCode.LIST_FAILED, e.awsErrorDetails().errorMessage());
		}
	}

	// DeleteObjects 는 한 요청당 최대 1000개 — 배치 분할 호출.
	public int deleteObjects(List<String> keys) {
		if (keys.isEmpty()) {
			return 0;
		}
		int deleted = 0;
		try {
			for (int i = 0; i < keys.size(); i += 1000) {
				List<ObjectIdentifier> identifiers = keys.subList(i, Math.min(i + 1000, keys.size())).stream()
					.map(k -> ObjectIdentifier.builder().key(k).build())
					.toList();
				DeleteObjectsRequest request = DeleteObjectsRequest.builder()
					.bucket(bucketName)
					.delete(Delete.builder().objects(identifiers).quiet(true).build())
					.build();
				// 요청은 200 이어도 키별로 실패할 수 있다 — 실패한 키는 지운 수에서 뺀다.
				DeleteObjectsResponse response = s3Client.deleteObjects(request);
				deleted += identifiers.size() - response.errors().size();
			}
		} catch (S3Exception e) {
			throw new S3StorageException(S3ErrorCode.DELETE_FAILED, e.awsErrorDetails().errorMessage());
		}
		return deleted;
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
