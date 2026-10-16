package sweetgirl.croissant.store.region;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "store.region-resolution.enabled", havingValue = "true")
public class StoreRegionResolutionScheduler {

	private static final String POLL_DELAY = "${store.region-resolution.poll-delay:PT30S}";
	private static final String INITIAL_DELAY = "${store.region-resolution.initial-delay:PT30S}";

	private final StoreRegionResolutionCommandService commandService;

	@Scheduled(fixedDelayString = POLL_DELAY, initialDelayString = INITIAL_DELAY)
	@SchedulerLock(name = "StoreRegionResolutionScheduler_refresh", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
	public void refresh() {
		int updated = commandService.refreshChangedStores();
		if (updated > 0) {
			log.info("[지역 소속] 좌표·경계 버전 갱신 완료 stores={}", updated);
		}
	}
}
