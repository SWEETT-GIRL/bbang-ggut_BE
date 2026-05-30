package sweetgirl.croissant.admin.service;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Option B — 일괄 백그라운드 enrich.
 * <p>
 * 어드민 대시보드 [전체 매칭 시작] 클릭 시 호출. Python 크롤러를 --loop 모드로 띄워서
 * 큐가 빌 때까지 백그라운드 처리. 진행상황은 기존 {@code /naver-enrich-stats} 폴링으로 추적.
 * <p>
 * 단일 인스턴스만 허용 — 동시에 두 개 돌면 같은 store 를 두 번 처리할 위험.
 */
@Slf4j
@Service
public class NaverEnrichOrchestrator {

	private static final int SIGTERM_GRACE_SECONDS = 5;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private volatile Process currentProcess;

	// 진행률 표시를 위해 시작 시점 정보를 메모리에 들고 있음.
	// JVM 재시작 시 잃지만, 새 작업 시작 시점부터는 정확하게 다시 채워짐.
	private volatile String runningMode;
	private volatile Instant startedAt;
	private volatile long startedQueueCount;

	@Value("${naver.crawler.python:python3}")
	private String pythonBinary;

	@Value("${naver.crawler.script:scripts/naver_menu_scraper.py}")
	private String scriptPath;

	@Value("${naver.crawler.backend-url:http://localhost:8080}")
	private String backendUrl;

	@Value("${naver.crawler.bulk-batch:50}")
	private int batchSize;

	public boolean isRunning() {
		return running.get();
	}

	public String getRunningMode() {
		return running.get() ? runningMode : null;
	}

	public Instant getStartedAt() {
		return running.get() ? startedAt : null;
	}

	public long getStartedQueueCount() {
		return running.get() ? startedQueueCount : 0L;
	}

	/**
	 * 일괄 처리 모드. 각 모드는 Python 크롤러 플래그와 진행률 표시용 라벨을 가진다.
	 */
	public enum Mode {
		// 검색 + 매칭 + enrich (naverPlaceId 미매칭 큐).
		ENRICH("enrich", null),
		// 사진만 보충 (매칭됨 + 사진 0장 큐).
		PHOTOS_BACKFILL("photos", "--photos-only"),
		// 풀 enrich 백필 (매칭됨인데 메뉴 OR 사진이 빈 큐) — 수동/후보 매칭으로 placeId 만 채워진 가게 자동 포함.
		ENRICH_BACKFILL("enrich-backfill", "--enrich-backfill");

		private final String label;
		private final String crawlerFlag;

		Mode(String label, String crawlerFlag) {
			this.label = label;
			this.crawlerFlag = crawlerFlag;
		}
	}

	/**
	 * @param mode 실행 모드 — 어느 큐를 어떤 깊이로 처리할지 결정.
	 * @param initialQueueCount 시작 시점의 큐 크기 — 진행률 % 계산용. 0 이면 진행률 표시 안 됨.
	 */
	public boolean tryStart(Mode mode, long initialQueueCount) {
		if (!running.compareAndSet(false, true)) {
			return false;
		}
		runningMode = mode.label;
		startedAt = Instant.now();
		startedQueueCount = initialQueueCount;
		Thread worker = new Thread(() -> runBulkLoop(mode), "naver-enrich-bulk");
		worker.setDaemon(true);
		worker.start();
		return true;
	}

	public void requestStop() {
		Process proc = currentProcess;
		if (proc == null || !proc.isAlive()) {
			return;
		}
		log.info("[NaverEnrich] 중단 요청 — SIGTERM 전송");
		proc.destroy();
		// Playwright 가 SIGTERM 을 가로채는 경우가 있어, 일정 시간 안 죽으면 SIGKILL 로 강제 종료
		Thread killer = new Thread(() -> {
			try {
				if (!proc.waitFor(SIGTERM_GRACE_SECONDS, TimeUnit.SECONDS)) {
					log.warn("[NaverEnrich] SIGTERM {}초 무시 — SIGKILL 강제 종료", SIGTERM_GRACE_SECONDS);
					proc.destroyForcibly();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				proc.destroyForcibly();
			}
		}, "naver-enrich-killer");
		killer.setDaemon(true);
		killer.start();
	}

	private void runBulkLoop(Mode mode) {
		log.info("[NaverEnrich] 일괄 시작 — mode={}, batch={}, backend={}", mode.label, batchSize, backendUrl);
		try {
			java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
				pythonBinary, "-u", scriptPath,
				"--server", backendUrl,
				"--loop",
				"--batch", String.valueOf(batchSize)));
			if (mode.crawlerFlag != null) {
				cmd.add(mode.crawlerFlag);
			}
			// -u 플래그: stdout/stderr unbuffered. SIGTERM 으로 종료될 때도 로그가 flush 되어 디버깅 가능.
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			pb.redirectOutput(new File("/tmp/naver-enrich-bulk.log"));
			currentProcess = pb.start();
			int exitCode = currentProcess.waitFor();
			log.info("[NaverEnrich] 일괄 종료 mode={}, exitCode={}", mode.label, exitCode);
		} catch (IOException e) {
			log.error("[NaverEnrich] subprocess 실행 실패", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("[NaverEnrich] 인터럽트 — 종료 처리");
		} finally {
			currentProcess = null;
			running.set(false);
		}
	}
}
