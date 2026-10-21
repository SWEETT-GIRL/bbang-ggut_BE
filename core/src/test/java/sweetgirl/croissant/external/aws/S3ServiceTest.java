package sweetgirl.croissant.external.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import sweetgirl.croissant.external.aws.dto.S3ObjectSummary;
import sweetgirl.croissant.external.aws.exception.S3StorageException;

@ExtendWith(MockitoExtension.class)
class S3ServiceTest {

	@Mock
	S3Presigner s3Presigner;
	@Mock
	S3Client s3Client;

	S3Service s3Service;

	@BeforeEach
	void setUp() {
		s3Service = new S3Service(s3Presigner, s3Client);
		ReflectionTestUtils.setField(s3Service, "bucketName", "test-bucket");
	}

	@Test
	@DisplayName("목록 조회는 다음 페이지 토큰을 따라가며 키·용량·수정 시각을 넘긴다")
	void forEachObject_followsContinuationToken() {
		Instant modifiedAt = Instant.parse("2026-07-15T00:00:00Z");
		given(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).willReturn(
			ListObjectsV2Response.builder()
				.contents(S3Object.builder().key("stores/1/a").size(100L).lastModified(modifiedAt).build())
				.isTruncated(true).nextContinuationToken("next").build(),
			ListObjectsV2Response.builder()
				.contents(S3Object.builder().key("stores/1/b").size(200L).lastModified(modifiedAt).build())
				.isTruncated(false).build());
		List<S3ObjectSummary> seen = new ArrayList<>();

		s3Service.forEachObject("stores/", seen::add);

		assertThat(seen).containsExactly(
			new S3ObjectSummary("stores/1/a", 100L, modifiedAt),
			new S3ObjectSummary("stores/1/b", 200L, modifiedAt));
		ArgumentCaptor<ListObjectsV2Request> requests = ArgumentCaptor.forClass(ListObjectsV2Request.class);
		then(s3Client).should(times(2)).listObjectsV2(requests.capture());
		assertThat(requests.getAllValues()).extracting(ListObjectsV2Request::prefix)
			.containsExactly("stores/", "stores/");
		assertThat(requests.getAllValues()).extracting(ListObjectsV2Request::continuationToken)
			.containsExactly(null, "next");
	}

	@Test
	@DisplayName("목록 조회가 실패하면 S3StorageException 으로 끝난다")
	void forEachObject_wrapsS3Exception() {
		given(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).willThrow(S3Exception.builder()
			.awsErrorDetails(AwsErrorDetails.builder().errorMessage("Access Denied").build()).build());

		List<S3ObjectSummary> seen = new ArrayList<>();

		assertThatThrownBy(() -> s3Service.forEachObject("stores/", seen::add))
			.isInstanceOf(S3StorageException.class);
	}

	@Test
	@DisplayName("일괄 삭제는 1000개씩 나눠 보내고 키별 실패는 지운 수에서 뺀다")
	void deleteObjects_countsOnlyDeleted() {
		List<String> keys = IntStream.range(0, 1500).mapToObj(index -> "stores/9/" + index).toList();
		given(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).willReturn(
			DeleteObjectsResponse.builder().build(),
			DeleteObjectsResponse.builder().errors(S3Error.builder().key("stores/9/1499").build()).build());

		int deleted = s3Service.deleteObjects(keys);

		assertThat(deleted).isEqualTo(1499);
		ArgumentCaptor<DeleteObjectsRequest> requests = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
		then(s3Client).should(times(2)).deleteObjects(requests.capture());
		assertThat(requests.getAllValues()).extracting(request -> request.delete().objects().size())
			.containsExactly(1000, 500);
	}
}
