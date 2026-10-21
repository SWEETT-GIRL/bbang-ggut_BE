package sweetgirl.croissant.config.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.external.aws.S3Service;

/**
 * actuator가 공개 API 포트와 분리된 관리 포트로만 노출되는지 검증한다.
 *
 * <p>운영에서는 관리 포트를 컨테이너 외부로 공개하지 않아 Prometheus 스크랩만 가능하다.
 *
 * <p>{@code src/test/resources/application.properties}가 main 설정을 대체하므로
 * 검증 대상 actuator 설정을 여기서 동일하게 명시한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"management.server.port=0",
	"management.endpoints.web.exposure.include=health,metrics,prometheus",
	"management.metrics.tags.application=Croissant",
	"management.metrics.distribution.percentiles-histogram.http.server.requests=true",
	"management.metrics.distribution.minimum-expected-value.http.server.requests=10ms",
	"management.metrics.distribution.maximum-expected-value.http.server.requests=10s",
	"server.tomcat.mbeanregistry.enabled=true"
})
class ActuatorEndpointExposureTest {

	@MockitoBean
	S3Service s3Service;

	@LocalServerPort
	private int serverPort;

	@LocalManagementPort
	private int managementPort;

	@Test
	@DisplayName("관리 포트의 prometheus 엔드포인트가 지표를 노출한다")
	void prometheusEndpointExposesMetrics() throws IOException, InterruptedException {
		HttpResponse<String> response = get(managementPort, "/actuator/prometheus");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body())
			.contains("jvm_memory_used_bytes")
			.contains("hikaricp_connections")
			.contains("application=\"Croissant\"");
	}

	@Test
	@DisplayName("Tomcat 스레드풀 지표가 노출된다 - MBean 레지스트리 활성화 확인")
	void tomcatThreadMetricsAreExposed() throws IOException, InterruptedException {
		HttpResponse<String> response = get(managementPort, "/actuator/prometheus");

		assertThat(response.body())
			.contains("tomcat_threads_busy_threads")
			.contains("tomcat_threads_current_threads");
	}

	@Test
	@DisplayName("HTTP 요청 지표가 히스토그램으로 기록된다 - p95 계산 가능 여부")
	void httpRequestMetricsUseHistogram() throws IOException, InterruptedException {
		// 히스토그램 버킷은 실제 요청이 한 번은 처리되어야 생성된다.
		get(serverPort, "/courses");

		HttpResponse<String> response = get(managementPort, "/actuator/prometheus");

		assertThat(response.body())
			.contains("# TYPE http_server_requests_seconds histogram")
			.contains("http_server_requests_seconds_bucket");
	}

	@Test
	@DisplayName("관리 포트의 health 엔드포인트가 UP을 반환한다 - 배포 헬스체크 경로")
	void healthEndpointReturnsUp() throws IOException, InterruptedException {
		HttpResponse<String> response = get(managementPort, "/actuator/health");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
	}

	@Test
	@DisplayName("공개 API 포트에서는 prometheus 지표에 접근할 수 없다")
	void prometheusIsNotExposedOnApplicationPort() throws IOException, InterruptedException {
		HttpResponse<String> response = get(serverPort, "/actuator/prometheus");

		assertThat(response.statusCode()).isNotEqualTo(200);
	}

	private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + path))
			.GET()
			.build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
	}
}
