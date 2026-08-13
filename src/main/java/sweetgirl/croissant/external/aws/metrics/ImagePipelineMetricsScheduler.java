package sweetgirl.croissant.external.aws.metrics;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.admin.dto.response.AdminS3ReconcileResponse;
import sweetgirl.croissant.admin.service.AdminS3ReconcileService;
import sweetgirl.croissant.external.aws.repository.ImagePendingCountRepository;
import sweetgirl.croissant.external.aws.repository.ImageProcessingCallbackRepository;

/**
 * 이미지 파이프라인 적체·정합성 지표를 주기적으로 갱신한다.
 *
 * <p>Gauge 람다를 스크랩 시점에 평가하게 두면 15초마다 count 쿼리가 돈다. 갱신 주기를 스크랩과
 * 분리해 쿼리 횟수를 고정한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImagePipelineMetricsScheduler {

	private final ImagePipelineMetrics imagePipelineMetrics;
	private final ImageProcessingCallbackRepository imageProcessingCallbackRepository;
	private final ImagePendingCountRepository imagePendingCountRepository;
	private final AdminS3ReconcileService adminS3ReconcileService;

	/**
	 * 적체 게이지 갱신.
	 *
	 * <p>보류 콜백은 테이블 하나라 비용이 없고, 미처리 이미지는 8개 테이블을 UNION ALL로 한 번에 센다
	 * (프로덕션 33만 행 기준 약 50ms). 1분 주기면 스크랩 주기와 무관하게 부하가 고정된다.
	 */
	@Scheduled(fixedDelay = 60_000L, initialDelay = 10_000L)
	@Transactional(readOnly = true)
	public void refreshBacklogGauges() {
		try {
			imagePipelineMetrics.updatePendingCallbacks(imageProcessingCallbackRepository.count());
			imagePipelineMetrics.updateProcessingPending(imagePendingCountRepository.countPendingImages());
		} catch (RuntimeException e) {
			// 지표 갱신 실패가 스케줄러를 멈추게 두지 않는다. 다음 주기에 다시 시도한다.
			log.warn("이미지 파이프라인 적체 지표 갱신 실패", e);
		}
	}

	/**
	 * S3와 DB의 정합성 지표 갱신.
	 *
	 * <p>버킷 전체 키를 나열하므로 하루 1회, 트래픽이 없는 새벽에만 돈다.
	 *
	 * <p><b>삭제하지 않고 세기만 한다({@code dryRun = true}).</b> 업로드는 끝났지만 등록 요청이
	 * 아직 도착하지 않은 파일이 고아로 잡히기 때문이다. 유예 기간 판정을 넣기 전까지는 수치만 관찰한다.
	 */
	@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
	public void refreshS3ReconcileGauges() {
		try {
			AdminS3ReconcileResponse result = adminS3ReconcileService.reconcile(true);
			imagePipelineMetrics.updateS3Reconcile(result.orphanCount(), result.missingCount());
			log.info("[s3-reconcile] s3Keys={} dbKeys={} orphans={} missing={}",
				result.s3KeyCount(), result.dbKeyCount(), result.orphanCount(), result.missingCount());
		} catch (RuntimeException e) {
			log.warn("S3 정합성 지표 갱신 실패", e);
		}
	}
}
