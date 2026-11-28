package sweetgirl.dataops.snapshot.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.util.Optional;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.dto.response.SnapshotSubmitResponse;
import sweetgirl.dataops.snapshot.entity.PlaceSnapshot;
import sweetgirl.dataops.reconcile.service.ReconcileService;
import sweetgirl.dataops.snapshot.exception.SnapshotException;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.repository.PlaceSnapshotRepository;
import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.enums.SyncTier;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SnapshotCommandServiceTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private PlaceSnapshotRepository placeSnapshotRepository;
	@Mock
	private StoreSyncStateRepository storeSyncStateRepository;

	@Mock
	private ReconcileService reconcileService;
	@Mock
	private org.springframework.transaction.PlatformTransactionManager transactionManager;
	@Mock
	private MenuEvidenceService menuEvidenceService;
	@Mock
	private sweetgirl.dataops.signal.service.StoreSignalService storeSignalService;
	@Mock
	private ResponseCaptureService responseCaptureService;
	@Mock
	private ResponseDiagnostics responseDiagnostics;

	private SnapshotCommandService snapshotCommandService;

	@BeforeEach
	void setUp() {
		NaverPlaceParser naverPlaceParser = new NaverPlaceParser();
		snapshotCommandService = new SnapshotCommandService(
			placeSnapshotRepository, storeSyncStateRepository,
			new ContentHashService(naverPlaceParser, objectMapper), naverPlaceParser,
			reconcileService, objectMapper,
			transactionManager, menuEvidenceService, storeSignalService,
			new sweetgirl.dataops.snapshot.parser.StoreSignalParser(
				naverPlaceParser, new sweetgirl.dataops.snapshot.parser.BusinessHoursParser()),
			new RegularHoursTracker(new sweetgirl.dataops.snapshot.parser.BusinessHoursParser(), objectMapper),
			responseCaptureService, responseDiagnostics);
		lenient().when(responseCaptureService.receive(any())).thenReturn("capture");
		lenient().when(responseCaptureService.isLatest(any())).thenReturn(true);
		lenient().when(responseCaptureService.isCurrentSnapshot(anyLong(), anyString(), any(), any(), any())).thenReturn(true);
		lenient().when(responseDiagnostics.inspect(any())).thenReturn(new ResponseDiagnostics.Report("parser", java.util.List.of()));
		lenient().when(menuEvidenceService.record(any())).thenReturn(
			new MenuEvidenceService.Recorded("visit", LocalDateTime.now(), false, true, true));
		lenient().when(menuEvidenceService.isLatest(any(), any())).thenReturn(true);
	}

	private StoreSyncState createSyncState() {
		return StoreSyncState.builder()
			.storeId(1L)
			.source(SyncSource.NAVER)
			.externalId("naver-123")
			.tier(SyncTier.T1)
			.build();
	}

	@Test
	void held_observation_keeps_raw_but_never_enters_evidence_hash_signal_or_reconcile() {
		given(responseDiagnostics.inspect(any())).willReturn(new ResponseDiagnostics.Report("parser", java.util.List.of(
			new ResponseDiagnostics.Issue("menus.price", "PRICE_SHAPE_UNSUPPORTED", "OBJECT", true))));
		var request = createRequest("{}");
		var response = snapshotCommandService.submit(request);
		assertThat(response.assessment()).isEqualTo("HELD");
		assertThat(response.captureId()).isEqualTo("capture");
		then(responseCaptureService).should().receive(request);
		then(responseCaptureService).should().assess(eq("capture"), eq("NAVER"), any());
		then(menuEvidenceService).shouldHaveNoInteractions();
		then(storeSyncStateRepository).shouldHaveNoInteractions();
		then(placeSnapshotRepository).shouldHaveNoInteractions();
		then(storeSignalService).shouldHaveNoInteractions();
		then(reconcileService).shouldHaveNoInteractions();
	}

	@Test void menu_only_partial_preserves_menu_baseline_and_signal_through_full_partial_full() {
		StoreSyncState state = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER)).willReturn(Optional.of(state));
		given(placeSnapshotRepository.save(any())).willAnswer(i -> i.getArgument(0));
		String fullA = new ResponseDiagnosticsTest().payload().toString();
		String fullBPayload = fullA.replace("빵집", "새빵집");
		var partialRaw = (tools.jackson.databind.node.ObjectNode)objectMapper.readTree(fullBPayload);
		((tools.jackson.databind.node.ObjectNode)partialRaw.path("ROOT_QUERY").path(ResponseDiagnosticsTest.ROOT)).putNull("placeMenus");
		partialRaw.remove("PlaceMenuItem:a");
		snapshotCommandService.submit(createRequest(fullA));
		String fullHash = state.getContentHash();
		String menuHash = state.getMenuHash();
		var menuTime = state.getMenuObservedAt();
		given(responseDiagnostics.inspect(any())).willReturn(new ResponseDiagnostics.Report("parser", java.util.List.of(
			new ResponseDiagnostics.Issue("menus", "MENU_INCOMPLETE", "NULL", false)), false,
			new sweetgirl.dataops.snapshot.dto.response.MenuSourceAvailability(
				sweetgirl.dataops.snapshot.dto.response.MenuSourceAvailability.State.NOT_PROVIDED,
				sweetgirl.dataops.snapshot.dto.response.MenuSourceAvailability.State.NOT_PROVIDED)));
		var partial = snapshotCommandService.submit(createRequest(partialRaw.toString()));
		assertThat(partial.assessment()).isEqualTo("PARTIAL");
		assertThat(partial.menuSources().naver().name()).isEqualTo("NOT_PROVIDED");
		assertThat(state.getNextDueAt()).isAfter(state.getLastSuccessAt());
		assertThat(partial.changed()).isTrue();
		assertThat(state.getContentHash()).isEqualTo(fullHash);
		assertThat(state.getMenuHash()).isEqualTo(menuHash);
		assertThat(state.getMenuObservedAt()).isEqualTo(menuTime);
		then(reconcileService).should().reconcile(eq(1L), any(), any(), eq(false), eq(false));
		then(storeSignalService).should(times(1)).refreshFromObservation(any(), any(), any(), any(), any(), anyBoolean());
		var again = snapshotCommandService.submit(createRequest(partialRaw.toString()));
		assertThat(again.changed()).isFalse();
		given(responseDiagnostics.inspect(any())).willReturn(new ResponseDiagnostics.Report("parser", java.util.List.of()));
		var fullB = snapshotCommandService.submit(createRequest(fullBPayload));
		assertThat(fullB.changed()).isFalse();
		assertThat(state.getChangeCount()).isEqualTo(2);
		then(placeSnapshotRepository).should(times(2)).save(any());
	}

	@Test void baemin_changes_advance_only_their_own_baseline_and_repeated_capture_does_not_reconcile_again() throws Exception {
		var state = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER)).willReturn(Optional.of(state));
		given(placeSnapshotRepository.save(any())).willAnswer(i -> i.getArgument(0));
		given(responseDiagnostics.inspect(any())).willReturn(new ResponseDiagnostics.Report("parser", java.util.List.of(
			new ResponseDiagnostics.Issue("placeMenus", "MENU_INCOMPLETE", "NULL", false)), true));
		String raw;
		try (var input = getClass().getResourceAsStream("/response-agent/baemin-only.json")) {
			raw = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
		var first = snapshotCommandService.submit(createRequest(raw));
		String initialHash = state.getBaeminMenuHash();
		assertThat(first.changed()).isTrue(); assertThat(first.assessment()).isEqualTo("PARTIAL");
		assertThat(state.getMenuHash()).isNull(); assertThat(state.getMenuObservedAt()).isNull();
		assertThat(initialHash).isNotBlank();
		assertThat(snapshotCommandService.submit(createRequest(raw)).changed()).isFalse();
		assertThat(snapshotCommandService.submit(createRequest(raw.replace("3500", "3700"))).changed()).isTrue();
		assertThat(state.getBaeminMenuHash()).isNotEqualTo(initialHash);
		assertThat(state.getMenuHash()).isNull();
		then(reconcileService).should(times(2)).reconcile(eq(1L), any(), any(), eq(false), eq(true));
		then(storeSignalService).shouldHaveNoInteractions();
		var receipt = new SnapshotSubmitResponse(10L, true, null, "capture", "PARTIAL", java.util.List.of());
		given(responseCaptureService.findProcessedResponse("capture")).willReturn(Optional.of(receipt));
		int visits = state.getVisitCount();
		assertThat(snapshotCommandService.submit(createRequest(raw))).isEqualTo(receipt);
		assertThat(state.getVisitCount()).isEqualTo(visits);
	}

	@Test void newer_held_capture_blocks_old_complete_submission() {
		StoreSyncState state = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER)).willReturn(Optional.of(state));
		given(responseCaptureService.isLatest(any())).willReturn(false);
		assertThat(snapshotCommandService.submit(createRequest(payloadWithReviews(100))).changed()).isFalse();
		assertThat(state.getVisitCount()).isZero();
		then(reconcileService).shouldHaveNoInteractions();
		then(storeSignalService).shouldHaveNoInteractions();
	}

	@Test void evidence_duplicate_without_completed_receipt_recovers_downstream_processing() {
		StoreSyncState state = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER)).willReturn(Optional.of(state));
		given(placeSnapshotRepository.save(any())).willAnswer(i -> i.getArgument(0));
		snapshotCommandService.submit(createRequest(payloadWithReviews(100)));
		given(menuEvidenceService.record(any())).willReturn(
			new MenuEvidenceService.Recorded("uncommitted-visit", LocalDateTime.now(), true, true, true));
		var response = snapshotCommandService.submit(createRequest(payloadWithReviews(110)));
		assertThat(response.changed()).isFalse();
		assertThat(state.getVisitCount()).isEqualTo(2);
		then(storeSignalService).should(times(2)).refreshFromObservation(any(), any(), any(), any(), any(), anyBoolean());
		then(responseCaptureService).should(times(2)).complete(eq("capture"), any());
	}

	private SnapshotSubmitRequest createRequest(String payloadJson) {
		return new SnapshotSubmitRequest(1L, SyncSource.NAVER, objectMapper.readTree(payloadJson), null);
	}

	/** 리뷰 수만 다른 두 페이로드 — 내용 해시는 같고 신호 해시만 달라진다 */
	private String payloadWithReviews(int reviewsTotal) {
		return """
				{
				  "ROOT_QUERY": {"placeDetail({id:1})": {"visitorReviewStats": null}},
				  "PlaceDetailBase:1": {"__typename": "PlaceDetailBase", "name": "빵집",
				    "visitorReviewsTotal": %d}
				}""".formatted(reviewsTotal);
	}

	@Test
	void submit_이미_지나간_배부의_늦은_도착은_방문도_신호도_움직이지_않는다() throws Exception {
		// 배부 세대가 오른 뒤 도착한 이전 시도 — 그 사이 새 배부가 더 최신 원본을 가져왔을 수 있다 (v3 P2-1)
		StoreSyncState syncState = createSyncState();
		org.springframework.test.util.ReflectionTestUtils.setField(syncState, "attemptSeq", 5L);
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER))
			.willReturn(Optional.of(syncState));

		SnapshotSubmitResponse response = snapshotCommandService.submit(new SnapshotSubmitRequest(
			1L, SyncSource.NAVER, objectMapper.readTree("{\"name\":\"빵집\"}"), null,
			"obs-late", "naver-123", java.time.OffsetDateTime.now(), 3L));

		assertThat(response.changed()).isFalse();
		assertThat(syncState.getVisitCount()).isZero();
		then(placeSnapshotRepository).should(never()).save(any(PlaceSnapshot.class));
		then(storeSignalService).should(never()).refreshFromObservation(
			any(), any(), any(), any(), any(), anyBoolean());
		then(reconcileService).shouldHaveNoInteractions();
	}

	@Test
	void submit_현재_배부_세대의_제출은_정상_처리한다() throws Exception {
		StoreSyncState syncState = createSyncState();
		org.springframework.test.util.ReflectionTestUtils.setField(syncState, "attemptSeq", 5L);
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER))
			.willReturn(Optional.of(syncState));
		given(placeSnapshotRepository.save(any(PlaceSnapshot.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		SnapshotSubmitResponse response = snapshotCommandService.submit(new SnapshotSubmitRequest(
			1L, SyncSource.NAVER, objectMapper.readTree("{\"name\":\"빵집\"}"), null,
			"obs-now", "naver-123", java.time.OffsetDateTime.now(), 5L));

		assertThat(response.changed()).isTrue();
		assertThat(syncState.getVisitCount()).isEqualTo(1);
	}

	@Test
	void submit_내용이_같아도_리뷰가_바뀌면_신호를_갱신한다() throws Exception {
		// P2 게이트 — 내용 해시가 같은 방문을 그냥 버리면 리뷰 갱신이 신호까지 도달하지 못한다.
		// 그 방문의 원본은 저장하지 않으므로 나중에 재파싱할 수도 없다 (로컬 실측 무변경 방문 22,013건 = 17.4%)
		StoreSyncState syncState = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER))
			.willReturn(Optional.of(syncState));
		given(placeSnapshotRepository.save(any(PlaceSnapshot.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		snapshotCommandService.submit(createRequest(payloadWithReviews(100)));
		SnapshotSubmitResponse second = snapshotCommandService.submit(createRequest(payloadWithReviews(101)));

		// 내용은 그대로라 스냅샷은 한 번만 저장된다
		assertThat(second.changed()).isFalse();
		then(placeSnapshotRepository).should(times(1)).save(any(PlaceSnapshot.class));
		// 그래도 신호 갱신은 두 번 호출된다 — 두 번째는 원본 미보존(false)으로
		then(storeSignalService).should(times(2)).refreshFromObservation(
			eq(1L), any(), any(), any(), any(), anyBoolean());
		then(storeSignalService).should().refreshFromObservation(eq(1L), isNull(), any(), any(), any(), eq(false));
	}

	@Test
	void submit_중복_관측은_신호를_갱신하지_않는다() throws Exception {
		// 같은 관측 ID 재전송 — 방문으로도 세지 않고 신호 근거 시각도 전진시키지 않는다
		StoreSyncState syncState = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER))
			.willReturn(Optional.of(syncState));
		given(placeSnapshotRepository.save(any(PlaceSnapshot.class)))
			.willAnswer(invocation -> invocation.getArgument(0));
		var completed = snapshotCommandService.submit(createRequest(payloadWithReviews(100)));
		given(responseCaptureService.findProcessedResponse("capture")).willReturn(Optional.of(completed));

		snapshotCommandService.submit(createRequest(payloadWithReviews(100)));

		assertThat(syncState.getVisitCount()).isEqualTo(1);
		then(storeSignalService).should(times(1)).refreshFromObservation(
			any(), any(), any(), any(), any(), anyBoolean());
	}

	@Test
	void submit_주소만_바뀐_재방문은_저장하고_재판정을_돌린다() throws Exception {
		// 1세대 해시는 주소를 안 봐서 이 재방문이 무변경으로 읽혔다 — 이전이 판정층에 도달하지 못했다 (v3 P2-1)
		StoreSyncState syncState = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER))
			.willReturn(Optional.of(syncState));
		given(placeSnapshotRepository.save(any(PlaceSnapshot.class)))
			.willAnswer(invocation -> invocation.getArgument(0));
		String before = "{\"PlaceDetailBase:1\": {\"__typename\": \"PlaceDetailBase\", \"name\": \"빵집\","
			+ " \"address\": \"서울 성동구 성수동 1-1\", \"coordinate\": {\"x\": \"127.05\", \"y\": \"37.54\"}}}";
		String moved = "{\"PlaceDetailBase:1\": {\"__typename\": \"PlaceDetailBase\", \"name\": \"빵집\","
			+ " \"address\": \"서울 광진구 구의동 9-9\", \"coordinate\": {\"x\": \"127.07\", \"y\": \"37.56\"}}}";

		snapshotCommandService.submit(createRequest(before));
		SnapshotSubmitResponse second = snapshotCommandService.submit(createRequest(moved));

		assertThat(second.changed()).isTrue();
		then(placeSnapshotRepository).should(times(2)).save(any(PlaceSnapshot.class));
		then(reconcileService).should(times(2)).reconcile(eq(1L), any(), any(), eq(true), eq(false));
	}

	@Test
	void submit_해시_세대만_다르면_변경으로_세지_않고_세대만_올린다() throws Exception {
		// 세대 전환 자체는 가게가 바뀐 것이 아니다 — change_count 에 합산하면 변경률이 오염된다
		StoreSyncState syncState = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER))
			.willReturn(Optional.of(syncState));
		String payload = "{\"PlaceDetailBase:1\": {\"__typename\": \"PlaceDetailBase\", \"name\": \"빵집\","
			+ " \"address\": \"서울 성동구 성수동 1-1\", \"coordinate\": {\"x\": \"127.05\", \"y\": \"37.54\"}}}";
		// 1세대 해시가 저장된 상태를 만든다
		ContentHashService hashService = new ContentHashService(new NaverPlaceParser(), objectMapper);
		String legacyHash = hashService.hashFor(
			new NaverPlaceParser().parse(objectMapper.readTree(payload)), (short)1);
		syncState.rebaselineHash(legacyHash);

		SnapshotSubmitResponse response = snapshotCommandService.submit(createRequest(payload));

		assertThat(response.changed()).isFalse();
		assertThat(syncState.getChangeCount()).isZero();
		assertThat(syncState.getContentHashVersion()).isEqualTo(ContentHashService.VERSION);
		assertThat(syncState.getContentHash()).isEqualTo(hashService.canonicalHash(
			new NaverPlaceParser().parse(objectMapper.readTree(payload))));
		then(placeSnapshotRepository).should(never()).save(any(PlaceSnapshot.class));
	}

	/** 영업시간만 다른 페이로드 — 이름·메뉴 등 영업시간 밖 내용은 같다 */
	private String payloadWithHours(String hoursJson) {
		return """
				{
				  "ROOT_QUERY": {"placeDetail({id:1})": {"newBusinessHours": [{"businessHours": %s}]}},
				  "PlaceDetailBase:1": {"__typename": "PlaceDetailBase", "name": "빵집"}
				}""".formatted(hoursJson);
	}

	private static final String SAT_REGULAR = "[{\"day\":\"금\",\"businessHours\":{\"start\":\"09:00\",\"end\":\"19:00\"}},"
		+ "{\"day\":\"토\",\"businessHours\":{\"start\":\"09:00\",\"end\":\"19:00\"}}]";
	private static final String SAT_HOLIDAY = "[{\"day\":\"금\",\"businessHours\":{\"start\":\"09:00\",\"end\":\"19:00\"}},"
		+ "{\"day\":\"토(9/26)\",\"businessHours\":null,\"description\":\"추석 휴무\"}]";
	private static final String FRI_LATER = "[{\"day\":\"금\",\"businessHours\":{\"start\":\"09:00\",\"end\":\"22:00\"}},"
		+ "{\"day\":\"토\",\"businessHours\":{\"start\":\"09:00\",\"end\":\"19:00\"}}]";

	@Test
	void submit_날짜_붙은_임시_요일만_달라진_재방문은_변경이_아니다() throws Exception {
		// 표본 2차 — "변경" 61곳 중 58곳이 이 경우였다. 3세대는 해시에서 영업시간을 빼고 정기 요일 기억과 비교한다
		StoreSyncState syncState = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER))
			.willReturn(Optional.of(syncState));
		given(placeSnapshotRepository.save(any(PlaceSnapshot.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		snapshotCommandService.submit(createRequest(payloadWithHours(SAT_REGULAR)));
		SnapshotSubmitResponse holiday = snapshotCommandService.submit(createRequest(payloadWithHours(SAT_HOLIDAY)));
		SnapshotSubmitResponse back = snapshotCommandService.submit(createRequest(payloadWithHours(SAT_REGULAR)));

		assertThat(holiday.changed()).isFalse();
		assertThat(back.changed()).isFalse();
		assertThat(syncState.getChangeCount()).isEqualTo(1);
		then(placeSnapshotRepository).should(times(1)).save(any(PlaceSnapshot.class));
		assertThat(syncState.getHoursKnown()).contains("SATURDAY").contains("FRIDAY");
	}

	@Test
	void submit_정기_요일_시간이_바뀌면_저장하고_재판정을_돌린다() throws Exception {
		StoreSyncState syncState = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER))
			.willReturn(Optional.of(syncState));
		given(placeSnapshotRepository.save(any(PlaceSnapshot.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		snapshotCommandService.submit(createRequest(payloadWithHours(SAT_REGULAR)));
		SnapshotSubmitResponse later = snapshotCommandService.submit(createRequest(payloadWithHours(FRI_LATER)));

		assertThat(later.changed()).isTrue();
		assertThat(syncState.getChangeCount()).isEqualTo(2);
		then(reconcileService).should(times(2)).reconcile(eq(1L), any(), any(), eq(true), eq(false));
		assertThat(syncState.getHoursKnown()).contains("\"close\":\"22:00\"");
	}

	@Test
	void submit_이세대_해시가_저장된_가게는_그_세대로_비교한_뒤_삼세대와_기억으로_넘어간다() throws Exception {
		StoreSyncState syncState = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER))
			.willReturn(Optional.of(syncState));
		ContentHashService hashService = new ContentHashService(new NaverPlaceParser(), objectMapper);
		String payload = payloadWithHours(SAT_REGULAR);
		syncState.recordSuccess(false, hashService.hashFor(
			new NaverPlaceParser().parse(objectMapper.readTree(payload)), (short)2), (short)2);

		SnapshotSubmitResponse response = snapshotCommandService.submit(createRequest(payload));

		assertThat(response.changed()).isFalse();
		assertThat(syncState.getContentHashVersion()).isEqualTo((short)3);
		assertThat(syncState.getHoursKnown()).contains("FRIDAY").contains("SATURDAY");
		then(placeSnapshotRepository).should(never()).save(any(PlaceSnapshot.class));
	}

	@Test
	void rebaseline_은_최신_스냅샷으로_삼세대_해시와_정기_영업시간_기억을_세운다() throws Exception {
		StoreSyncState syncState = createSyncState();
		org.springframework.test.util.ReflectionTestUtils.setField(syncState, "id", 9L);
		syncState.recordSuccess(true, "old-v2-hash", (short)2);
		int visits = syncState.getVisitCount();
		given(storeSyncStateRepository.findAll()).willReturn(java.util.List.of(syncState));
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER)).willReturn(Optional.of(syncState));
		// 최신(추석 주)은 토요일에 날짜가 붙었고, 그 전 스냅샷에는 정기 토요일이 있다
		given(placeSnapshotRepository.findTop6ByStoreIdAndSourceOrderByFetchedAtDesc(1L, SyncSource.NAVER))
			.willReturn(java.util.List.of(
				PlaceSnapshot.builder().storeId(1L).source(SyncSource.NAVER)
					.fetchedAt(LocalDateTime.of(2026, 9, 25, 12, 0)).payload(payloadWithHours(SAT_HOLIDAY)).build(),
				PlaceSnapshot.builder().storeId(1L).source(SyncSource.NAVER)
					.fetchedAt(LocalDateTime.of(2026, 9, 6, 8, 0)).payload(payloadWithHours(SAT_REGULAR)).build()));

		int rebaselined = snapshotCommandService.rebaselineContentHashes();

		assertThat(rebaselined).isEqualTo(1);
		assertThat(syncState.getContentHashVersion()).isEqualTo((short)3);
		assertThat(syncState.getContentHash()).isNotEqualTo("old-v2-hash").hasSize(64);
		// 금요일은 최신 관측 시각, 토요일은 날짜가 붙기 전 관측에서 온다 — 다음 방문이 "처음 보이는 토요일"이 되지 않게
		assertThat(syncState.getHoursKnown())
			.contains("\"FRIDAY\":{\"open\":\"09:00\",\"close\":\"19:00\",\"closed\":false,\"observedAt\":\"2026-09-25T12:00\"")
			.contains("\"SATURDAY\":{\"open\":\"09:00\",\"close\":\"19:00\",\"closed\":false,\"observedAt\":\"2026-09-06T08:00\"");
		// 카운터·스케줄은 건드리지 않는다
		assertThat(syncState.getVisitCount()).isEqualTo(visits);
		// 다시 돌려도 같은 결과면 갱신하지 않는다
		assertThat(snapshotCommandService.rebaselineContentHashes()).isZero();
	}

	@Test void rebaseline_preserves_more_recent_hours_and_days_outside_snapshot_window() {
		var state = createSyncState();
		state.recordSuccess(true, "old-hash", (short)2);
		String memory = "{\"MONDAY\":{\"open\":\"10:00\",\"close\":\"20:00\",\"closed\":false,\"observedAt\":\"2026-10-10T10:00\"},"
			+ "\"FRIDAY\":{\"open\":\"11:00\",\"close\":\"22:00\",\"closed\":false,\"observedAt\":\"2026-10-12T10:00\"}}";
		state.rememberHours(memory);
		given(storeSyncStateRepository.findAll()).willReturn(java.util.List.of(state));
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER)).willReturn(Optional.of(state));
		given(placeSnapshotRepository.findTop6ByStoreIdAndSourceOrderByFetchedAtDesc(1L, SyncSource.NAVER))
			.willReturn(java.util.List.of(PlaceSnapshot.builder().storeId(1L).source(SyncSource.NAVER)
				.fetchedAt(LocalDateTime.of(2026, 10, 1, 10, 0)).payload(payloadWithHours(SAT_REGULAR)).build()));
		snapshotCommandService.rebaselineContentHashes();
		var after = objectMapper.readTree(state.getHoursKnown());
		assertThat(after.path("MONDAY")).isEqualTo(objectMapper.readTree(memory).path("MONDAY"));
		assertThat(after.path("FRIDAY")).isEqualTo(objectMapper.readTree(memory).path("FRIDAY"));
		assertThat(after.has("SATURDAY")).isTrue();
	}

	@Test void rebaseline_skips_snapshot_older_than_accepted_store_scope() {
		var state = createSyncState();
		state.recordScopedSuccess(true, "complete", (short)3, "store", "menu", (short)1,
			LocalDateTime.of(2026, 10, 12, 10, 0));
		given(storeSyncStateRepository.findAll()).willReturn(java.util.List.of(state));
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER)).willReturn(Optional.of(state));
		given(placeSnapshotRepository.findTop6ByStoreIdAndSourceOrderByFetchedAtDesc(1L, SyncSource.NAVER))
			.willReturn(java.util.List.of(PlaceSnapshot.builder().storeId(1L).source(SyncSource.NAVER)
				.fetchedAt(LocalDateTime.of(2026, 10, 1, 10, 0)).payload(payloadWithHours(SAT_REGULAR)).build()));
		assertThat(snapshotCommandService.rebaselineContentHashes()).isZero();
		assertThat(state.getNonMenuHash()).isEqualTo("store");
		assertThat(state.getMenuHash()).isEqualTo("menu");
	}

	@Test
	void submit_변경된_페이로드는_저장하고_변경_카운터를_올린다() throws Exception {
		StoreSyncState syncState = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER))
			.willReturn(Optional.of(syncState));
		given(placeSnapshotRepository.save(any(PlaceSnapshot.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		SnapshotSubmitResponse response = snapshotCommandService.submit(createRequest("{\"name\":\"빵집\"}"));

		assertThat(response.changed()).isTrue();
		assertThat(response.contentHash()).hasSize(64);
		assertThat(syncState.getVisitCount()).isEqualTo(1);
		assertThat(syncState.getChangeCount()).isEqualTo(1);
		assertThat(syncState.getContentHash()).isEqualTo(response.contentHash());
	}

	@Test
	void submit_동일_해시면_저장을_생략하고_방문_카운터만_올린다() throws Exception {
		StoreSyncState syncState = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER))
			.willReturn(Optional.of(syncState));
		given(placeSnapshotRepository.save(any(PlaceSnapshot.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		snapshotCommandService.submit(createRequest("{\"name\":\"빵집\"}"));
		SnapshotSubmitResponse second = snapshotCommandService.submit(createRequest("{\"name\":\"빵집\"}"));

		assertThat(second.changed()).isFalse();
		assertThat(second.snapshotId()).isNull();
		assertThat(syncState.getVisitCount()).isEqualTo(2);
		assertThat(syncState.getChangeCount()).isEqualTo(1);
		then(placeSnapshotRepository).should(times(1)).save(any(PlaceSnapshot.class));
		then(menuEvidenceService).should(times(2)).record(any());
	}

	@Test
	void submit_변경_시에만_판정층을_트리거한다() throws Exception {
		StoreSyncState syncState = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER))
			.willReturn(Optional.of(syncState));
		given(placeSnapshotRepository.save(any(PlaceSnapshot.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		snapshotCommandService.submit(createRequest("{\"name\":\"빵집\"}"));
		snapshotCommandService.submit(createRequest("{\"name\":\"빵집\"}"));

		then(reconcileService).should(times(1)).reconcile(eq(1L), any(), any(), eq(true), eq(false));
	}

	@Test
	void submit_sync_state_가_없으면_예외를_던진다() {
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER))
			.willReturn(Optional.empty());

		SnapshotSubmitRequest request = createRequest("{}");

		assertThatThrownBy(() -> snapshotCommandService.submit(request))
			.isInstanceOf(SnapshotException.class);
	}

	@Test
	void duplicate_observation_does_not_increment_visit_again() {
		StoreSyncState syncState = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER)).willReturn(Optional.of(syncState));
		given(placeSnapshotRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
		SnapshotSubmitRequest request = createRequest("{}");
		var completed = snapshotCommandService.submit(request);
		given(responseCaptureService.findProcessedResponse("capture")).willReturn(Optional.of(completed));

		snapshotCommandService.submit(request);

		assertThat(syncState.getVisitCount()).isEqualTo(1);
		then(placeSnapshotRepository).should(times(1)).save(any());
	}

	@Test
	void late_observation_is_saved_but_cannot_rewind_snapshot_or_schedule() {
		StoreSyncState syncState = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER)).willReturn(Optional.of(syncState));
		given(menuEvidenceService.isLatest(any(), any())).willReturn(false);

		assertThat(snapshotCommandService.submit(createRequest("{}")).changed()).isFalse();

		assertThat(syncState.getVisitCount()).isZero();
		then(menuEvidenceService).should().record(any());
		then(placeSnapshotRepository).shouldHaveNoInteractions();
		then(reconcileService).shouldHaveNoInteractions();
	}

	@Test
	void wrong_target_never_updates_snapshot_or_schedule() {
		StoreSyncState syncState = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER)).willReturn(Optional.of(syncState));
		given(menuEvidenceService.record(any())).willReturn(
			new MenuEvidenceService.Recorded("wrong", LocalDateTime.now(), false, false, false));

		assertThatThrownBy(() -> snapshotCommandService.submit(createRequest("{}")))
			.isInstanceOf(SnapshotException.class);

		assertThat(syncState.getVisitCount()).isZero();
		then(placeSnapshotRepository).shouldHaveNoInteractions();
		then(reconcileService).shouldHaveNoInteractions();
	}

	@Test
	void evidence_is_recorded_before_failing_reconcile() {
		StoreSyncState syncState = createSyncState();
		given(storeSyncStateRepository.findForSnapshotUpdate(1L, SyncSource.NAVER)).willReturn(Optional.of(syncState));
		given(placeSnapshotRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
		willThrow(new IllegalStateException("reconcile failed")).given(reconcileService).reconcile(eq(1L), any(), any(), eq(true), eq(false));

		assertThatThrownBy(() -> snapshotCommandService.submit(createRequest("{}"))).isInstanceOf(IllegalStateException.class);

		var order = inOrder(menuEvidenceService, reconcileService);
		order.verify(menuEvidenceService).record(any());
		order.verify(menuEvidenceService).isLatest(any(), any());
		order.verify(reconcileService).reconcile(eq(1L), any(), any(), eq(true), eq(false));
	}
}
