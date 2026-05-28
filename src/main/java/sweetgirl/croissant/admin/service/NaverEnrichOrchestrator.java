package sweetgirl.croissant.admin.service;

import java.io.File;
import java.io.IOException;
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

	private final AtomicBoolean running = new AtomicBoolean(false);
	private volatile Process currentProcess;

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

	public boolean tryStart() {
		if (!running.compareAndSet(false, true)) {
			return false;
		}
		Thread worker = new Thread(this::runBulkLoop, "naver-enrich-bulk");
		worker.setDaemon(true);
		worker.start();
		return true;
	}

	public void requestStop() {
		Process proc = currentProcess;
		if (proc != null && proc.isAlive()) {
			log.info("[NaverEnrich] 중단 요청 — SIGTERM 전송");
			proc.destroy();
		}
	}

	private void runBulkLoop() {
		log.info("[NaverEnrich] 일괄 시작 — batch={}, backend={}", batchSize, backendUrl);
		try {
			// -u 플래그: stdout/stderr unbuffered. SIGTERM 으로 종료될 때도 로그가 flush 되어 디버깅 가능.
			ProcessBuilder pb = new ProcessBuilder(
				pythonBinary, "-u", scriptPath,
				"--server", backendUrl,
				"--loop",
				"--batch", String.valueOf(batchSize));
			pb.redirectErrorStream(true);
			pb.redirectOutput(new File("/tmp/naver-enrich-bulk.log"));
			currentProcess = pb.start();
			int exitCode = currentProcess.waitFor();
			log.info("[NaverEnrich] 일괄 종료 exitCode={}", exitCode);
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
