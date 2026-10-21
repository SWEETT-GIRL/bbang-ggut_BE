package sweetgirl.croissant.personalization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.repository.UserTasteSignalJdbcRepository;

@ExtendWith(MockitoExtension.class)
class SignatureKindBaseRatesTest {

	@Mock
	private UserTasteSignalJdbcRepository signalRepository;

	@Test
	@DisplayName("유효 시간 안에는 한 번만 읽는다 — 검사 제출 경로가 매번 집계 쿼리를 돌지 않게")
	void current_cachesWithinTtl() {
		PersonalizationProperties properties = new PersonalizationProperties();
		given(signalRepository.findSignatureKindBaseRates(0.5)).willReturn(Map.of("소금빵", 0.2));
		SignatureKindBaseRates baseRates = new SignatureKindBaseRates(signalRepository, properties);

		assertThat(baseRates.current()).containsEntry("소금빵", 0.2);
		assertThat(baseRates.current()).containsEntry("소금빵", 0.2);
		verify(signalRepository, times(1)).findSignatureKindBaseRates(0.5);
	}

	@Test
	@DisplayName("reload 는 캐시와 상관없이 새로 읽는다 — 전체 재계산 배치는 방금 적재된 값을 써야 한다")
	void reload_alwaysReads() {
		PersonalizationProperties properties = new PersonalizationProperties();
		given(signalRepository.findSignatureKindBaseRates(0.5)).willReturn(Map.of("소금빵", 0.2), Map.of("소금빵", 0.3));
		SignatureKindBaseRates baseRates = new SignatureKindBaseRates(signalRepository, properties);

		baseRates.current();
		assertThat(baseRates.reload()).containsEntry("소금빵", 0.3);
		assertThat(baseRates.current()).containsEntry("소금빵", 0.3);
	}

	@Test
	@DisplayName("유효 시간이 지나면 current 가 새로 읽는다")
	void current_reloadsAfterTtl() {
		PersonalizationProperties properties = new PersonalizationProperties();
		properties.getProfile().setKindBaseRateTtl(Duration.ZERO);
		given(signalRepository.findSignatureKindBaseRates(0.5)).willReturn(Map.of("소금빵", 0.2));
		SignatureKindBaseRates baseRates = new SignatureKindBaseRates(signalRepository, properties);

		baseRates.current();
		baseRates.current();
		verify(signalRepository, times(2)).findSignatureKindBaseRates(0.5);
	}
}
