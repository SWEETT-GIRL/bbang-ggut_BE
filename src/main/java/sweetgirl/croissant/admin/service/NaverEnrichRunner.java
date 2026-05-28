package sweetgirl.croissant.admin.service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.admin.dto.response.AdminNaverEnrichRunResponse;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

/**
 * Option A — 단일 가게 sync enrich.
 * <p>
 * 어드민 웹에서 한 행 [Enrich] 클릭 시 호출. Python 크롤러를 subprocess 로 띄워서 한 가게만 처리.
 * 완료 후 DB 재조회해서 결과 반환 (subprocess 가 backend API 로 결과를 이미 저장한 상태).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NaverEnrichRunner {

	private final StoreRepository storeRepository;
	private final MenuRepository menuRepository;

	@Value("${naver.crawler.python:python3}")
	private String pythonBinary;

	@Value("${naver.crawler.script:scripts/naver_menu_scraper.py}")
	private String scriptPath;

	@Value("${naver.crawler.backend-url:http://localhost:8080}")
	private String backendUrl;

	@Value("${naver.crawler.single-timeout-seconds:180}")
	private int timeoutSeconds;

	@Transactional(readOnly = true)
	public AdminNaverEnrichRunResponse runForStore(Long storeId) {
		Store before = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		log.info("[NaverEnrich] 단일 가게 시작 - id={}, name={}", storeId, before.getName());

		// -u 플래그: stdout/stderr unbuffered (타임아웃 시 강제 종료에도 로그 flush 보장).
		ProcessBuilder pb = new ProcessBuilder(
			pythonBinary, "-u", scriptPath,
			"--store-id", String.valueOf(storeId),
			"--server", backendUrl);
		pb.redirectErrorStream(true);

		Process process;
		try {
			process = pb.start();
		} catch (IOException e) {
			log.error("[NaverEnrich] subprocess 실행 실패", e);
			return new AdminNaverEnrichRunResponse(false, null, null, 0, -1,
				"크롤러 실행 실패: " + e.getMessage());
		}

		boolean finished;
		try {
			finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			return new AdminNaverEnrichRunResponse(false, null, null, 0, -1, "인터럽트됨");
		}
		if (!finished) {
			process.destroyForcibly();
			log.warn("[NaverEnrich] 타임아웃 - id={}, timeout={}s", storeId, timeoutSeconds);
			return new AdminNaverEnrichRunResponse(false, null, null, 0, -1,
				"타임아웃 (" + timeoutSeconds + "초)");
		}

		int exitCode = process.exitValue();

		// subprocess 가 이미 DB 에 결과를 저장했으므로 다시 읽어옴.
		Store after = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		long menuCount = menuRepository.countActiveMenusByStoreIds(List.of(storeId)).stream()
			.findFirst()
			.map(row -> (Long)row[1])
			.orElse(0L);

		boolean matched = after.getNaverPlaceId() != null;
		String msg = matched
			? "매칭 성공 (naverPlaceId=" + after.getNaverPlaceId() + ", 메뉴 " + menuCount + "개)"
			: "매칭 실패 또는 신뢰 매칭 못 찾음";
		log.info("[NaverEnrich] 단일 가게 완료 - id={}, matched={}, exitCode={}", storeId, matched, exitCode);

		return new AdminNaverEnrichRunResponse(
			matched,
			after.getNaverPlaceId(),
			after.getNaverMatchedAt() != null ? after.getNaverMatchedAt().toString() : null,
			menuCount,
			exitCode,
			msg);
	}
}
