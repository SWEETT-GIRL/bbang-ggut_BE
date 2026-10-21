package sweetgirl.croissant.admin.bulkimport.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.admin.bulkimport.config.BulkImportAsyncConfig;
import sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgress;
import sweetgirl.croissant.admin.bulkimport.loader.DongListLoader;
import sweetgirl.croissant.admin.bulkimport.loader.DongRow;

@Slf4j
@Service
@RequiredArgsConstructor
public class BulkImportOrchestrator {

	private final DongListLoader dongListLoader;
	private final BulkImportProgressService progressService;
	private final DongImporter dongImporter;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean shouldStop = new AtomicBoolean(false);

	@Value("${app.startup-data-maintenance.enabled:true}")
	private boolean startupDataMaintenanceEnabled = true;

	public boolean tryStart() {
		boolean acquired = running.compareAndSet(false, true);
		if (acquired) {
			shouldStop.set(false);
		}
		return acquired;
	}

	public boolean isRunning() {
		return running.get();
	}

	public void requestStop() {
		shouldStop.set(true);
	}

	@Async(BulkImportAsyncConfig.EXECUTOR_NAME)
	public void runImportAsync() {
		runImport();
	}

	public void runImport() {
		try {
			List<DongRow> allDongs = dongListLoader.load();
			log.info("[BulkImport] 시작 — 전체 동 수: {}", allDongs.size());

			long existingTotal = progressService.countTotal();
			if (existingTotal >= allDongs.size()) {
				log.info("[BulkImport] Phase 1 skip — 메타 적재 이미 완료 (existing={}, csv={})",
					existingTotal, allDongs.size());
			} else {
				for (DongRow row : allDongs) {
					progressService.findOrCreate(row.region1st(), row.region2nd(), row.region3rd());
				}
			}

			List<BulkImportProgress> targets = progressService.getPendingOrFailed();
			log.info("[BulkImport] 처리 대상 동 수: {} (전체 중 PENDING+FAILED)", targets.size());

			int processed = 0;
			for (BulkImportProgress target : targets) {
				if (shouldStop.get()) {
					log.info("[BulkImport] 중단 요청 수신 — 진행 종료");
					break;
				}
				processOneDong(target);
				processed++;
				if (processed % 100 == 0) {
					log.info("[BulkImport] 진행률 {}/{} ({}%)",
						processed, targets.size(),
						String.format("%.1f", processed * 100.0 / targets.size()));
				}
			}
			log.info("[BulkImport] 종료 — 처리 동 수: {}", processed);
		} catch (Exception e) {
			log.error("[BulkImport] 예상치 못한 오류로 orchestrator 종료", e);
		} finally {
			running.set(false);
			shouldStop.set(false);
		}
	}

	private void processOneDong(BulkImportProgress progress) {
		long startMs = System.currentTimeMillis();
		try {
			progressService.markInProgress(progress);
			DongImporter.DongImportResult result = dongImporter.importDong(
				progress.getRegion1st(), progress.getRegion2nd(), progress.getRegion3rd());
			progressService.markCompleted(progress, result.foundCount(), result.insertedCount());
			long elapsedMs = System.currentTimeMillis() - startMs;
			log.info("[BulkImport] {} {} {} - found={}, inserted={} ({}ms)",
				progress.getRegion1st(), progress.getRegion2nd(), progress.getRegion3rd(),
				result.foundCount(), result.insertedCount(), elapsedMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			progressService.markFailed(progress, "interrupted");
		} catch (Exception e) {
			log.warn("[BulkImport] 동 처리 실패 - {} {} {}, err={}",
				progress.getRegion1st(), progress.getRegion2nd(), progress.getRegion3rd(),
				e.getMessage());
			progressService.markFailed(progress, e.getMessage());
		}
	}

	@EventListener
	public void recoverStaleOnStartup(ApplicationReadyEvent event) {
		if (!startupDataMaintenanceEnabled) {
			return;
		}

		int recovered = progressService.recoverStaleInProgress();
		if (recovered > 0) {
			log.info("[BulkImport] 시작 시 stale IN_PROGRESS {} 건을 PENDING으로 복구", recovered);
		}
	}
}
