package sweetgirl.croissant.personalization.service;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.repository.UserTasteSignalJdbcRepository;

/**
 * 종류별 전체 빵집 비율(추정 종류의 lift 분모)을 잠깐 들고 있는다. 가게 프로필은 dataops 적재 때만 바뀌므로 매 재계산마다 집계할 필요가
 * 없다 — 검사 제출처럼 사용자가 기다리는 경로가 집계 쿼리를 돌지 않게 한다. 전체 재계산 배치는 시작할 때 {@link #reload()} 로 새로 읽는다.
 */
@Component
@RequiredArgsConstructor
public class SignatureKindBaseRates {

	private final UserTasteSignalJdbcRepository signalRepository;
	private final PersonalizationProperties personalizationProperties;

	private volatile Map<String, Double> rates = Map.of();
	private volatile Instant loadedAt = Instant.EPOCH;

	/** 캐시가 살아 있으면 그대로, 아니면 새로 읽는다 */
	public Map<String, Double> current() {
		if (!Instant.now().isBefore(loadedAt.plus(personalizationProperties.getProfile().getKindBaseRateTtl()))) {
			return reload();
		}
		return rates;
	}

	public synchronized Map<String, Double> reload() {
		rates = Map.copyOf(signalRepository.findSignatureKindBaseRates(
			personalizationProperties.getProfile().getKindBaseMinBakeryScore()));
		loadedAt = Instant.now();
		return rates;
	}
}
