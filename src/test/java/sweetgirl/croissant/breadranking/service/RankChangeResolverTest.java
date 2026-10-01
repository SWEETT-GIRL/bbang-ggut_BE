package sweetgirl.croissant.breadranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadranking.repository.RankingSnapshotJdbcRepository;

@ExtendWith(MockitoExtension.class)
class RankChangeResolverTest {

	private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 1);
	private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);

	@Mock
	private RankingSnapshotJdbcRepository snapshotRepository;

	@InjectMocks
	private RankChangeResolver resolver;

	@Test
	@DisplayName("어제 스냅샷이 있으면 그 판의 등수로 변동을 만든다")
	void resolvesFromYesterday() {
		given(snapshotRepository.existsForDate(THURSDAY.minusDays(1))).willReturn(true);
		given(snapshotRepository.findRanks(THURSDAY.minusDays(1), "RECORDS", 0, "서울_동대문구")).willReturn(Map.of(7L, 3));

		RankChanges changes = resolver.resolve("RECORDS", 0, "서울_동대문구", THURSDAY);

		assertThat(changes.known()).isTrue();
		assertThat(changes.changeOf(7L, 1)).isEqualTo(2);
	}

	@Test
	@DisplayName("어제 스냅샷이 아예 없으면(첫날) 모른다")
	void unknownWhenNoSnapshot() {
		given(snapshotRepository.existsForDate(THURSDAY.minusDays(1))).willReturn(false);

		assertThat(resolver.resolve("RECORDS", 0, "", THURSDAY).known()).isFalse();
		verify(snapshotRepository, never()).findRanks(any(), anyString(), anyLong(), anyString());
	}

	@Test
	@DisplayName("주간 판은 월요일에 비교하지 않는다 — 스냅샷을 읽지도 않는다")
	void weeklyOnMondayIsUnknown() {
		assertThat(resolver.resolve("WEEKLY", 0, "", MONDAY).known()).isFalse();
		verify(snapshotRepository, never()).existsForDate(any());
	}

	@Test
	@DisplayName("지역 키 — 둘 다 있어야 '시도_시군구', 아니면 전국('')")
	void regionKey() {
		assertThat(RankChangeResolver.regionKey("서울", "동대문구")).isEqualTo("서울_동대문구");
		assertThat(RankChangeResolver.regionKey(null, null)).isEmpty();
		assertThat(RankChangeResolver.regionKey("서울", null)).isEmpty();
	}
}
