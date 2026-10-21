package sweetgirl.dataops.sync.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.code.ServingErrorCode;
import sweetgirl.dataops.serving.exception.ServingException;
import sweetgirl.dataops.sync.dto.response.ObservationSampleResponse;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.enums.SyncStateStatus;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;

/**
 * 1단계 관찰 표본 선정 — 표본만 due 로 두고 나머지는 next_due_at NULL 로 보류한다.
 * 메뉴 0개 가게 우선(리서치 2.5 — 최초 수집 누락 복구가 최대 가치) + 메뉴 보유 가게 일부(파서
 * 정합성 눈검사·해시 변경률 대표성). md5(storeId) 순 결정적 추출이라 재실행해도 같은 표본이 나온다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncSampleService {

	private static final double MENU_ZERO_RATIO = 0.8;
	private static final int SCHEDULE_CHUNK_SIZE = 500;

	private final ObjectProvider<ServingReader> servingReaderProvider;
	private final StoreSyncStateRepository storeSyncStateRepository;

	@Transactional
	public ObservationSampleResponse selectObservationSample(int sampleSize) {
		ServingReader servingReader = servingReaderProvider.getIfAvailable();
		if (servingReader == null) {
			throw new ServingException(ServingErrorCode.PROD_DB_NOT_CONFIGURED);
		}

		Set<Long> menuZeroIds = new HashSet<>(servingReader.findMenuZeroMatchedStoreIds());
		List<Long> allStoreIds = storeSyncStateRepository.findStoreIdsBySource(SyncSource.NAVER);
		Comparator<Long> deterministicOrder = Comparator.comparing(this::md5OfId);

		int menuZeroTarget = (int)(sampleSize * MENU_ZERO_RATIO);
		List<Long> menuZeroSample = allStoreIds.stream()
			.filter(menuZeroIds::contains)
			.sorted(deterministicOrder)
			.limit(menuZeroTarget)
			.toList();
		List<Long> withMenuSample = allStoreIds.stream()
			.filter(storeId -> !menuZeroIds.contains(storeId))
			.sorted(deterministicOrder)
			.limit(sampleSize - menuZeroSample.size())
			.toList();

		int cleared = storeSyncStateRepository.clearSchedule(SyncSource.NAVER, SyncStateStatus.ACTIVE);

		LocalDateTime now = LocalDateTime.now();
		List<Long> sample = new ArrayList<>(menuZeroSample);
		sample.addAll(withMenuSample);
		int scheduled = 0;
		for (int from = 0; from < sample.size(); from += SCHEDULE_CHUNK_SIZE) {
			List<Long> chunk = sample.subList(from, Math.min(from + SCHEDULE_CHUNK_SIZE, sample.size()));
			scheduled += storeSyncStateRepository.scheduleStores(
				SyncSource.NAVER, SyncStateStatus.ACTIVE, chunk, now);
		}

		log.info("관찰 표본 선정 — 표본 {}곳(메뉴0 {} + 메뉴보유 {}), 보류 {}곳",
			scheduled, menuZeroSample.size(), withMenuSample.size(), cleared - scheduled);
		return new ObservationSampleResponse(
			sample.size(), menuZeroSample.size(), withMenuSample.size(), scheduled, cleared - scheduled);
	}

	private String md5OfId(Long storeId) {
		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			return HexFormat.of()
				.formatHex(digest.digest(String.valueOf(storeId).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("MD5 미지원 JVM", e);
		}
	}
}
