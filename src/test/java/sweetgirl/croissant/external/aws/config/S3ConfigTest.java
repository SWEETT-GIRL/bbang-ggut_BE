package sweetgirl.croissant.external.aws.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

class S3ConfigTest {

	private static final String OCI_ENDPOINT = "https://examplens.compat.objectstorage.ap-tokyo-1.oraclecloud.com";

	@Test
	@DisplayName("주소를 주지 않으면 지금처럼 AWS S3 주소(버킷이 호스트)로 서명한다")
	void presigner_withoutEndpoint_usesAwsVirtualHostedUrl() {
		StorageProperties properties = properties("ap-northeast-2");

		URL url = presignPut(new S3Config(properties).s3Presigner());

		assertThat(url.getHost()).isEqualTo("test-bucket.s3.ap-northeast-2.amazonaws.com");
		assertThat(url.getPath()).isEqualTo("/stores/1/photo");
	}

	@Test
	@DisplayName("주소와 경로 방식을 주면 그 주소에 버킷을 경로로 붙여 서명한다")
	void presigner_withEndpointAndPathStyle_usesEndpointWithBucketInPath() {
		StorageProperties properties = properties("ap-tokyo-1");
		properties.setEndpoint(OCI_ENDPOINT);
		properties.setPathStyleAccess(true);

		URL url = presignPut(new S3Config(properties).s3Presigner());

		assertThat(url.getHost()).isEqualTo("examplens.compat.objectstorage.ap-tokyo-1.oraclecloud.com");
		assertThat(url.getPath()).isEqualTo("/test-bucket/stores/1/photo");
	}

	@Test
	@DisplayName("서명된 업로드 주소에는 클라이언트가 보낼 수 없는 체크섬 값이 실리지 않는다")
	void presigner_withEndpoint_doesNotRequireChecksumValue() {
		StorageProperties properties = properties("ap-tokyo-1");
		properties.setEndpoint(OCI_ENDPOINT);
		properties.setPathStyleAccess(true);

		URL url = presignPut(new S3Config(properties).s3Presigner());

		assertThat(url.getQuery())
			.doesNotContain("x-amz-checksum-crc32=")
			.doesNotContain("x-amz-sdk-checksum-algorithm")
			.contains("X-Amz-SignedHeaders=content-type%3Bhost");
	}

	@Test
	@DisplayName("주소를 주면 S3 클라이언트도 그 주소로 요청한다")
	void client_withEndpoint_overridesEndpoint() {
		StorageProperties properties = properties("ap-tokyo-1");
		properties.setEndpoint(OCI_ENDPOINT);
		properties.setPathStyleAccess(true);

		try (S3Client client = new S3Config(properties).s3Client()) {
			assertThat(client.serviceClientConfiguration().endpointOverride()).contains(URI.create(OCI_ENDPOINT));
		}
	}

	@Test
	@DisplayName("주소를 주면 일괄 삭제 요청에 Content-MD5 가 실린다 — S3 호환 스토리지는 CRC32 만으로는 받지 않을 수 있다")
	void client_withEndpoint_sendsContentMd5OnBulkDelete() {
		StorageProperties properties = properties("ap-tokyo-1");
		properties.setEndpoint(OCI_ENDPOINT);
		properties.setPathStyleAccess(true);

		Map<String, List<String>> headers = bulkDeleteHeaders(new S3Config(properties).s3Client());

		assertThat(headers).containsKey("Content-MD5");
	}

	@Test
	@DisplayName("주소를 주지 않으면 일괄 삭제 요청은 AWS 기본 체크섬 그대로다")
	void client_withoutEndpoint_keepsDefaultChecksumOnBulkDelete() {
		Map<String, List<String>> headers = bulkDeleteHeaders(new S3Config(properties("ap-northeast-2")).s3Client());

		assertThat(headers).containsKey("x-amz-checksum-crc32").doesNotContainKey("Content-MD5");
	}

	@Test
	@DisplayName("주소를 주지 않으면 S3 클라이언트는 주소를 덮어쓰지 않는다")
	void client_withoutEndpoint_keepsDefaultEndpoint() {
		StorageProperties properties = properties("ap-northeast-2");
		properties.setEndpoint(" ");

		try (S3Client client = new S3Config(properties).s3Client()) {
			assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty();
		}
	}

	@Test
	@DisplayName("주소 형식이 틀리면 조용히 AWS 로 붙지 않고 빈 생성에서 실패한다")
	void client_withMalformedEndpoint_fails() {
		StorageProperties properties = properties("ap-tokyo-1");
		properties.setEndpoint("https://exa mple");

		assertThatThrownBy(() -> new S3Config(properties).s3Client())
			.isInstanceOf(IllegalArgumentException.class);
	}

	private StorageProperties properties(String region) {
		StorageProperties properties = new StorageProperties();
		properties.setRegion(region);
		properties.getCredentials().setAccessKey("test-access-key");
		properties.getCredentials().setSecretKey("test-secret-key");
		return properties;
	}

	/** 전송 직전의 헤더를 잡고 요청을 끊는다 — 네트워크로는 나가지 않는다. */
	private Map<String, List<String>> bulkDeleteHeaders(S3Client client) {
		Map<String, List<String>> captured = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		ExecutionInterceptor captor = new ExecutionInterceptor() {
			@Override
			public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
				captured.putAll(context.httpRequest().headers());
				throw new IllegalStateException("captured");
			}
		};
		DeleteObjectsRequest request = DeleteObjectsRequest.builder()
			.bucket("test-bucket")
			.delete(Delete.builder().objects(ObjectIdentifier.builder().key("stores/1/photo").build()).build())
			.overrideConfiguration(override -> override.addPlugin(
				config -> config
					.overrideConfiguration(clientOverride -> clientOverride.addExecutionInterceptor(captor))))
			.build();

		try (client) {
			assertThatThrownBy(() -> client.deleteObjects(request)).hasStackTraceContaining("captured");
		}
		return captured;
	}

	private URL presignPut(S3Presigner presigner) {
		try (presigner) {
			PutObjectRequest objectRequest = PutObjectRequest.builder()
				.bucket("test-bucket")
				.key("stores/1/photo")
				.contentType("image/jpeg")
				.build();
			return presigner.presignPutObject(PutObjectPresignRequest.builder()
				.signatureDuration(Duration.ofMinutes(5))
				.putObjectRequest(objectRequest)
				.build()).url();
		}
	}
}
