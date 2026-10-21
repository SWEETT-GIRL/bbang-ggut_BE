package sweetgirl.croissant.external.aws.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import sweetgirl.croissant.admin.dto.response.AdminS3ReconcileResponse;
import sweetgirl.croissant.admin.service.AdminS3ReconcileService;
import sweetgirl.croissant.external.aws.repository.ImagePendingCountRepository;
import sweetgirl.croissant.external.aws.repository.ImageProcessingCallbackRepository;

@ExtendWith(MockitoExtension.class)
class ImagePipelineMetricsSchedulerTest {

	@Mock
	private ImageProcessingCallbackRepository imageProcessingCallbackRepository;

	@Mock
	private ImagePendingCountRepository imagePendingCountRepository;

	@Mock
	private AdminS3ReconcileService adminS3ReconcileService;

	private SimpleMeterRegistry meterRegistry;
	private ImagePipelineMetrics metrics;
	private ImagePipelineMetricsScheduler scheduler;

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
		metrics = new ImagePipelineMetrics(meterRegistry);
		// 운영에서는 @PostConstruct로 실행되는 시계열 사전 등록을 동일하게 태운다.
		metrics.registerMeters();
		scheduler = new ImagePipelineMetricsScheduler(metrics, imageProcessingCallbackRepository,
			imagePendingCountRepository, adminS3ReconcileService);
	}

	private double gauge(String name) {
		Gauge gauge = meterRegistry.find(name).gauge();
		return gauge == null ? -1 : gauge.value();
	}

	@Test
	@DisplayName("적체 게이지는 보류 콜백 수와 미처리 이미지 수를 각각 반영한다")
	void refreshBacklogGauges_updatesBothGauges() {
		given(imageProcessingCallbackRepository.count()).willReturn(3L);
		given(imagePendingCountRepository.countPendingImages()).willReturn(10L);

		scheduler.refreshBacklogGauges();

		assertThat(gauge("image.pending.callbacks")).isEqualTo(3.0);
		assertThat(gauge("image.processing.pending")).isEqualTo(10.0);
	}

	@Test
	@DisplayName("적체 조회가 실패해도 스케줄러가 멈추지 않는다")
	void refreshBacklogGauges_swallowsException() {
		willThrow(new IllegalStateException("DB 연결 끊김"))
			.given(imageProcessingCallbackRepository).count();

		// 지표 갱신 실패로 다음 주기까지 스케줄러가 죽으면 안 된다
		assertThatCode(() -> scheduler.refreshBacklogGauges()).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("S3 정합성 게이지는 삭제 없이 dryRun으로만 수치를 갱신한다")
	void refreshS3ReconcileGauges_runsDryRunOnly() {
		given(adminS3ReconcileService.reconcile(true)).willReturn(
			AdminS3ReconcileResponse.builder().dryRun(true).s3KeyCount(1000).dbKeyCount(990)
				.orphanCount(12).orphanKeys(List.of()).missingCount(2).missingKeys(List.of()).build());

		scheduler.refreshS3ReconcileGauges();

		// 업로드 직후 등록 요청이 아직 오지 않은 파일을 고아로 오인해 지우면 안 되므로 항상 dryRun이다
		verify(adminS3ReconcileService).reconcile(true);
		assertThat(gauge("s3.orphan.objects")).isEqualTo(12.0);
		assertThat(gauge("s3.missing.objects")).isEqualTo(2.0);
	}

	@Test
	@DisplayName("S3 정합성 조회가 실패해도 스케줄러가 멈추지 않는다")
	void refreshS3ReconcileGauges_swallowsException() {
		willThrow(new IllegalStateException("S3 접근 실패"))
			.given(adminS3ReconcileService).reconcile(true);

		assertThatCode(() -> scheduler.refreshS3ReconcileGauges()).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("게이지·카운터 시계열은 부팅 시 전부 등록되어 발생 전에도 0으로 노출된다")
	void registerMeters_preRegistersEverySeries() {
		assertThat(gauge("image.pending.callbacks")).isZero();
		assertThat(gauge("image.processing.pending")).isZero();
		assertThat(gauge("s3.orphan.objects")).isZero();
		assertThat(gauge("s3.missing.objects")).isZero();
		// GIF 실패를 진짜 실패와 구분하려면 mime_type 조합이 미리 떠 있어야 한다
		assertThat(meterRegistry.find("image.callback").counters()).isNotEmpty();
		assertThat(meterRegistry.find("image.orphan.callback").counter()).isNotNull();
	}
}
