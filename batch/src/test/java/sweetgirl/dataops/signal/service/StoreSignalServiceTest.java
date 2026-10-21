package sweetgirl.dataops.signal.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.dataops.signal.entity.StoreSignal;
import sweetgirl.dataops.signal.repository.StoreSignalRepository;
import sweetgirl.dataops.snapshot.parser.BusinessHoursParser;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.parser.ParsedStoreSignal;
import sweetgirl.dataops.snapshot.parser.StoreSignalParser;
import sweetgirl.dataops.snapshot.repository.PlaceSnapshotRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * 관측 기반 신호 갱신 (v3 P2-2) — 내용 해시가 같아 스냅샷이 저장되지 않은 방문도 신호까지 도달해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class StoreSignalServiceTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private StoreSignalRepository storeSignalRepository;
	@Mock
	private PlaceSnapshotRepository placeSnapshotRepository;
	@Mock
	private org.springframework.transaction.PlatformTransactionManager transactionManager;

	@Mock
	private sweetgirl.dataops.sync.repository.StoreSyncStateRepository storeSyncStateRepository;
	@Mock
	private sweetgirl.dataops.snapshot.service.ResponseCaptureService responseCaptureService;

	private StoreSignalService service;

	@BeforeEach
	void setUp() {
		NaverPlaceParser naverPlaceParser = new NaverPlaceParser();
		service = new StoreSignalService(storeSignalRepository, placeSnapshotRepository,
			new StoreSignalParser(naverPlaceParser, new BusinessHoursParser()), objectMapper, transactionManager,
			storeSyncStateRepository, new sweetgirl.dataops.snapshot.service.ResponseDiagnostics(objectMapper,
				naverPlaceParser, new BusinessHoursParser(),
				new sweetgirl.dataops.snapshot.parser.MenuEvidenceParser(objectMapper)), responseCaptureService);
	}

	private ParsedStoreSignal signal(int reviewsTotal) {
		return new ParsedStoreSignal("베이커리", "BAKERY", null, List.of(), List.of(), List.of(),
			4.5, reviewsTotal, reviewsTotal, List.of(), List.of(), List.of(), List.of(), List.of(),
			false, List.of(), 0, 0, List.of(), List.of());
	}

	private StoreSignal existing(ParsedStoreSignal parsed, LocalDateTime observedAt, boolean payloadRetained) {
		return StoreSignal.builder()
			.storeId(1L).snapshotId(10L).fetchedAt(observedAt).category(parsed.category())
			.visitorReviewsTotal(parsed.visitorReviewsTotal()).parserVersion(2)
			.signals(objectMapper.writeValueAsString(parsed))
			.signalHash(service.signalHash(parsed)).signalObservedAt(observedAt)
			.observationId("obs-1").payloadRetained(payloadRetained)
			.build();
	}

	@Test
	void 신호_해시는_파서_결과가_같으면_같다() {
		assertThat(service.signalHash(signal(100))).isEqualTo(service.signalHash(signal(100)));
		assertThat(service.signalHash(signal(100))).isNotEqualTo(service.signalHash(signal(101)));
	}

	@Test
	void 값이_바뀌면_스냅샷_없이도_신호를_갱신한다() {
		LocalDateTime before = LocalDateTime.of(2026, 9, 26, 9, 0);
		StoreSignal stored = existing(signal(100), before, true);
		given(storeSignalRepository.findById(1L)).willReturn(Optional.of(stored));

		boolean changed = service.refreshFromObservation(1L, null, signal(101),
			before.plusHours(1), "obs-2", false);

		assertThat(changed).isTrue();
		assertThat(stored.getVisitorReviewsTotal()).isEqualTo(101);
		// 스냅샷 id 는 기존 것을 유지한다 — 이 관측의 원본은 저장되지 않았다
		assertThat(stored.getSnapshotId()).isEqualTo(10L);
		assertThat(stored.isPayloadRetained()).isFalse();
		assertThat(stored.getSignalObservedAt()).isEqualTo(before.plusHours(1));
	}

	@Test
	void 값이_같으면_근거_시각만_전진시킨다() {
		LocalDateTime before = LocalDateTime.of(2026, 9, 26, 9, 0);
		StoreSignal stored = existing(signal(100), before, true);
		given(storeSignalRepository.findById(1L)).willReturn(Optional.of(stored));

		boolean changed = service.refreshFromObservation(1L, null, signal(100),
			before.plusHours(6), "obs-2", false);

		assertThat(changed).isFalse();
		assertThat(stored.getSignalObservedAt()).isEqualTo(before.plusHours(6));
		// 원본 보존 여부는 한 번 true 였으면 유지한다 — 재파싱 가능성이 사라진 것은 아니다
		assertThat(stored.isPayloadRetained()).isTrue();
	}

	@Test
	void 늦게_도착한_과거_관측은_근거_시각을_되돌리지_않는다() {
		LocalDateTime now = LocalDateTime.of(2026, 9, 26, 12, 0);
		StoreSignal stored = existing(signal(100), now, true);
		given(storeSignalRepository.findById(1L)).willReturn(Optional.of(stored));

		service.refreshFromObservation(1L, null, signal(100), now.minusDays(3), "obs-late", false);

		assertThat(stored.getSignalObservedAt()).isEqualTo(now);
	}

	@Test
	void 기존_행도_원본도_없으면_신호를_만들지_않는다() {
		// 근거를 가리킬 스냅샷이 없다 — snapshot_id 를 지어내지 않는다
		given(storeSignalRepository.findById(1L)).willReturn(Optional.empty());

		boolean changed = service.refreshFromObservation(1L, null, signal(100),
			LocalDateTime.now(), "obs-1", false);

		assertThat(changed).isFalse();
		then(storeSignalRepository).should(never()).save(any(StoreSignal.class));
	}
	private sweetgirl.dataops.snapshot.entity.PlaceSnapshot snapshot(boolean menuComplete) {
		String payload = "{\"ROOT_QUERY\":{\"placeDetail({\\\"input\\\":{\\\"id\\\":\\\"p-1\\\"}})\":{"
			+ "\"id\":\"p-1\",\"placeMenus\":{\"menuCount\":" + (menuComplete ? "1" : "2")
			+ ",\"items\":[{\"__ref\":\"Menu:1\"}]}}},"
			+ "\"PlaceDetailBase:p-1\":{\"id\":\"p-1\",\"name\":\"빵집\"},"
			+ "\"Menu:1\":{\"id\":\"1\",\"__typename\":\"Menu\",\"name\":\"소금빵\",\"price\":\"3000\"}}";
		var snapshot = sweetgirl.dataops.snapshot.entity.PlaceSnapshot.builder().storeId(1L)
			.source(sweetgirl.dataops.sync.enums.SyncSource.NAVER).fetchedAt(LocalDateTime.of(2026, 10, 13, 12, 0))
			.payload(payload).build();
		org.springframework.test.util.ReflectionTestUtils.setField(snapshot, "id", 10L);
		return snapshot;
	}

	private void givenReplay(sweetgirl.dataops.snapshot.entity.PlaceSnapshot snapshot, boolean current) {
		given(placeSnapshotRepository.findTopByStoreIdAndSourceOrderByFetchedAtDesc(1L,
			sweetgirl.dataops.sync.enums.SyncSource.NAVER)).willReturn(Optional.of(snapshot));
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, sweetgirl.dataops.sync.enums.SyncSource.NAVER))
			.willReturn(Optional.of(sweetgirl.dataops.sync.entity.StoreSyncState.builder().storeId(1L)
				.source(sweetgirl.dataops.sync.enums.SyncSource.NAVER).externalId("p-1").build()));
		given(responseCaptureService.isCurrentSnapshot(eq(1L), eq("NAVER"), eq("p-1"), any(), any())).willReturn(current);
	}

	@Test
	void 메뉴_불완전_원본의_직접_재빌드는_기존_신호와_근거시각을_그대로_보존한다() {
		var latest = snapshot(false);
		var stored = existing(signal(100), latest.getFetchedAt().minusDays(1), true);
		givenReplay(latest, true);
		given(storeSignalRepository.findById(1L)).willReturn(Optional.of(stored));

		assertThatThrownBy(() -> service.rebuild(1L))
			.isInstanceOf(sweetgirl.dataops.signal.exception.SignalException.class);

		assertThat(stored.getVisitorReviewsTotal()).isEqualTo(100);
		assertThat(stored.getSignalObservedAt()).isEqualTo(latest.getFetchedAt().minusDays(1));
		assertThat(stored.getSignals()).isEqualTo(objectMapper.writeValueAsString(signal(100)));
		then(storeSignalRepository).should(never()).save(any());
	}

	@Test
	void 전체_재빌드도_메뉴_불완전_원본을_건너뛴다() {
		givenReplay(snapshot(false), true);
		given(placeSnapshotRepository.findDistinctStoreIdsBySource(sweetgirl.dataops.sync.enums.SyncSource.NAVER))
			.willReturn(List.of(1L));

		assertThat(service.rebuildAll()).isZero();

		then(storeSignalRepository).should(never()).save(any());
	}

	@Test
	void 최신_응답과_다른_저장원본은_신호_재빌드를_하지_않는다() {
		givenReplay(snapshot(true), false);

		assertThatThrownBy(() -> service.rebuild(1L))
			.isInstanceOf(sweetgirl.dataops.signal.exception.SignalException.class);

		then(storeSignalRepository).should(never()).save(any());
	}

	@Test
	void 완전하고_최신인_저장원본은_신호를_재빌드한다() {
		givenReplay(snapshot(true), true);
		given(storeSignalRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

		assertThat(service.rebuild(1L)).isNotNull();

		then(storeSignalRepository).should().save(any());
		var ordering = org.mockito.Mockito.inOrder(storeSyncStateRepository, placeSnapshotRepository, storeSignalRepository);
		ordering.verify(storeSyncStateRepository).findForSnapshotUpdate(1L, sweetgirl.dataops.sync.enums.SyncSource.NAVER);
		ordering.verify(placeSnapshotRepository).findTopByStoreIdAndSourceOrderByFetchedAtDesc(1L, sweetgirl.dataops.sync.enums.SyncSource.NAVER);
		ordering.verify(storeSignalRepository, times(2)).findById(1L);
	}

	@Test
	void 내용이_다른_과거_관측도_최신_신호값을_되돌리지_않는다() {
		LocalDateTime now = LocalDateTime.of(2026, 10, 13, 12, 0);
		StoreSignal stored = existing(signal(100), now, true);
		given(storeSignalRepository.findById(1L)).willReturn(Optional.of(stored));

		assertThat(service.refreshFromObservation(1L, null, signal(50), now.minusDays(1), "old", false)).isFalse();

		assertThat(stored.getVisitorReviewsTotal()).isEqualTo(100);
		assertThat(stored.getSignalObservedAt()).isEqualTo(now);
	}

}
