package sweetgirl.dataops.reconcile.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.dataops.proposal.service.ProposalCommandService;
import sweetgirl.dataops.reconcile.enums.ReconcileMode;
import sweetgirl.dataops.reconcile.policy.BeverageFilter;
import sweetgirl.dataops.reconcile.policy.MenuCategoryClassifier;
import sweetgirl.dataops.reconcile.policy.MergePolicy;
import sweetgirl.dataops.reconcile.repository.StoreFieldProvenanceRepository;
import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.dto.MenuCurrentValues;
import sweetgirl.dataops.serving.dto.StoreCurrentValues;
import sweetgirl.dataops.snapshot.parser.ParsedMenu;
import sweetgirl.dataops.snapshot.parser.ParsedPlace;
import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ReconcileServiceTest {

	@Mock
	private ObjectProvider<ServingReader> servingReaderProvider;
	@Mock
	private ServingReader servingReader;
	@Mock
	private ProposalCommandService proposalCommandService;
	@Mock
	private StoreFieldProvenanceRepository provenanceRepository;
	@Mock
	private StoreSyncStateRepository storeSyncStateRepository;
	@Mock
	private sweetgirl.dataops.proposal.repository.ChangeProposalRepository changeProposalRepository;
	@Mock
	private sweetgirl.dataops.snapshot.repository.PlaceSnapshotRepository placeSnapshotRepository;

	@Mock
	private sweetgirl.dataops.snapshot.service.ResponseCaptureService responseCaptureService;

	private ReconcileService reconcileService;

	@BeforeEach
	void setUp() {
		MergePolicy mergePolicy = new MergePolicy();
		ReflectionTestUtils.setField(mergePolicy, "mode", ReconcileMode.PROPOSAL_ONLY);
		reconcileService = new ReconcileService(
			servingReaderProvider, proposalCommandService, mergePolicy, new BeverageFilter(), new MenuCategoryClassifier(),
			provenanceRepository, storeSyncStateRepository, changeProposalRepository,
			placeSnapshotRepository, new sweetgirl.dataops.snapshot.parser.NaverPlaceParser(),
			new sweetgirl.dataops.snapshot.parser.BusinessHoursParser(),
			new sweetgirl.dataops.snapshot.service.ResponseDiagnostics(new ObjectMapper(),
				new sweetgirl.dataops.snapshot.parser.NaverPlaceParser(),
				new sweetgirl.dataops.snapshot.parser.BusinessHoursParser(),
				new sweetgirl.dataops.snapshot.parser.MenuEvidenceParser(new ObjectMapper())),
			responseCaptureService,
			org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class),
			new ObjectMapper());
		ReflectionTestUtils.setField(reconcileService, "enabled", true);
		org.mockito.Mockito.lenient().when(responseCaptureService.isCurrentSnapshot(anyLong(), anyString(),
			anyString(), any(), any())).thenReturn(true);
		org.mockito.Mockito.lenient().when(storeSyncStateRepository.findForSnapshotUpdate(anyLong(), eq(SyncSource.NAVER)))
			.thenAnswer(invocation -> Optional.of(StoreSyncState.builder().storeId(invocation.getArgument(0))
				.source(SyncSource.NAVER).externalId("p-" + invocation.getArgument(0)).build()));
	}

	@Test
	void 판정_도중_실패하면_범위_정리를_하지_않아_그_가게의_이전_제안이_남는다() {
		// AC-09 — 정리가 판정 뒤에 있으므로 중간 예외는 정리에 닿지 못한다. 앞이라면 이전 세대가 날아간 채로 끝난다
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(servingReader.findStore(1L)).willReturn(Optional.of(
			new StoreCurrentValues(1L, "옛 이름", "서울 성동구 성수동 1-1", "02-1", "ACTIVE", null, 127.05, 37.54)));
		given(servingReader.findMenus(1L)).willThrow(new RuntimeException("운영 조회 실패"));

		assertThatThrownBy(() -> reconcileTrusted(1L, 10L,
			parsedPlace("새 이름", null, List.of(), List.of(), null)))
			.isInstanceOf(RuntimeException.class);

		then(changeProposalRepository).should(never())
			.expireOutOfGeneration(any(), any(), any(), any(), anyInt(), any());
	}

	@Test
	void 판정이_끝나면_이_가게_이_producer_이번에_본_묶음만_정리한다() {
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(servingReader.findStore(1L)).willReturn(Optional.of(
			new StoreCurrentValues(1L, "옛 이름", "서울 성동구 성수동 1-1", "02-1", "ACTIVE", null, 127.05, 37.54)));
		given(servingReader.findMenus(1L)).willReturn(List.of());

		reconcileTrusted(1L, 10L,
			parsedPlace("새 이름", null, List.of(), List.of(), null));

		ArgumentCaptor<java.util.Collection<sweetgirl.dataops.proposal.enums.ProposalTargetFamily>> families =
			ArgumentCaptor.captor();
		then(changeProposalRepository).should().expireOutOfGeneration(eq(1L),
			eq(sweetgirl.dataops.proposal.enums.ProposalProducer.NAVER_SNAPSHOT_RECONCILE),
			families.capture(), any(), anyInt(), any());
		// 지역·상태·분류·매칭은 다른 producer 의 묶음이라 범위에 없어야 한다
		assertThat(families.getValue()).doesNotContain(
			sweetgirl.dataops.proposal.enums.ProposalTargetFamily.STORE_REGION,
			sweetgirl.dataops.proposal.enums.ProposalTargetFamily.STORE_STATUS,
			sweetgirl.dataops.proposal.enums.ProposalTargetFamily.STORE_CATEGORY,
			sweetgirl.dataops.proposal.enums.ProposalTargetFamily.STORE_NAVER_PLACE_ID);
	}

	@Test
	void capture_replay_reuses_existing_proposal_without_rewriting_origin_or_expiring_other_pending() {
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(servingReader.findStore(1L)).willReturn(Optional.of(
			new StoreCurrentValues(1L, "old", "address", null, "ACTIVE", null, null, null)));
		var oldRun = java.util.UUID.randomUUID();
		var existing = sweetgirl.dataops.proposal.entity.ChangeProposal.builder().storeId(1L).target("store.name")
			.oldValue("\"old\"").newValue("\"new\"").snapshotId(88L)
			.source(sweetgirl.dataops.reconcile.enums.ProvenanceSource.NAVER)
			.producer(sweetgirl.dataops.proposal.enums.ProposalProducer.LEGACY_UNKNOWN).reconcileRunId(oldRun).build();
		ReflectionTestUtils.setField(existing, "id", 44L);
		given(proposalCommandService.propose(eq(1L), eq("store.name"), any(), any(), any(), isNull(), any(), any(), any()))
			.willReturn(sweetgirl.dataops.proposal.dto.response.ChangeProposalResponse.from(existing));
		given(changeProposalRepository.findById(44L)).willReturn(Optional.of(existing));
		var result = reconcileService.reconcileCapturedReplay(1L, parsedPlace("new", null, List.of(), List.of(), null), false);
		assertThat(result).hasSize(1);
		assertThat(result.get(0).created()).isFalse();
		assertThat(result.get(0).originalSnapshotId()).isEqualTo(88L);
		assertThat(existing.getReconcileRunId()).isEqualTo(oldRun);
		assertThat(existing.getProducer()).isEqualTo(sweetgirl.dataops.proposal.enums.ProposalProducer.LEGACY_UNKNOWN);
		then(changeProposalRepository).should(never()).expireOutOfGeneration(any(), any(), any(), any(), anyInt(), any());
		then(storeSyncStateRepository).shouldHaveNoInteractions();
		then(placeSnapshotRepository).shouldHaveNoInteractions();
	}

	@Test
	void capture_replay_identifies_new_generation_without_creating_observation_snapshot() {
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(servingReader.findStore(1L)).willReturn(Optional.of(
			new StoreCurrentValues(1L, "old", "address", null, "ACTIVE", null, null, null)));
		given(proposalCommandService.propose(eq(1L), eq("store.name"), any(), any(), any(), isNull(), any(), any(), any()))
			.willAnswer(call -> {
				var created = sweetgirl.dataops.proposal.entity.ChangeProposal.builder().storeId(1L).target("store.name")
					.oldValue(call.getArgument(2)).newValue(call.getArgument(3)).source(call.getArgument(4))
					.snapshotId(null).producer(call.getArgument(6)).policyVersion(call.getArgument(7)).reconcileRunId(call.getArgument(8)).build();
				ReflectionTestUtils.setField(created, "id", 55L);
				given(changeProposalRepository.findById(55L)).willReturn(Optional.of(created));
				return sweetgirl.dataops.proposal.dto.response.ChangeProposalResponse.from(created);
			});
		var result = reconcileService.reconcileCapturedReplay(1L, parsedPlace("new", null, List.of(), List.of(), null), false);
		assertThat(result).hasSize(1);
		assertThat(result.get(0).created()).isTrue();
		assertThat(result.get(0).originalSnapshotId()).isNull();
		then(placeSnapshotRepository).shouldHaveNoInteractions();
		then(storeSyncStateRepository).shouldHaveNoInteractions();
	}

	/** Existing field-policy tests supply an already validated complete observation. */
	private void reconcileTrusted(Long storeId, Long snapshotId, ParsedPlace place) {
		reconcileService.reconcile(storeId, snapshotId, place, true);
	}

	@Test
	void 메뉴_보류에서는_정상_이름과_영업시간만_제안하고_메뉴_기존세대는_만료하지_않는다() {
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(servingReader.findStore(1L)).willReturn(Optional.of(
			new StoreCurrentValues(1L, "옛 이름", "주소", null, "ACTIVE", null, null, null)));
		String hours = "[{\"day\":\"월\",\"businessHours\":{\"start\":\"10:00\",\"end\":\"20:00\"}}]";
		ParsedPlace partial = new ParsedPlace("새 이름", null, null, null, null, null, null, hours, null,
			List.of(naverMenu("가격변경 메뉴", "7000")), List.of(baeminMenu("새 메뉴", "4000", "빵")));

		reconcileService.reconcile(1L, 10L, partial, false);

		then(proposalCommandService).should().propose(eq(1L), eq("store.name"), any(), any(), any(), eq(10L), any(), any(), any());
		then(proposalCommandService).should().propose(eq(1L), eq("store.business_hours"), any(), any(), any(), eq(10L), any(), any(), any());
		then(proposalCommandService).should(never()).propose(any(), startsWith("menu."), any(), any(), any(), any(), any(), any(), any());
		then(servingReader).should(never()).findMenus(anyLong());
		ArgumentCaptor<java.util.Collection<sweetgirl.dataops.proposal.enums.ProposalTargetFamily>> families = ArgumentCaptor.captor();
		then(changeProposalRepository).should().expireOutOfGeneration(eq(1L), any(), families.capture(), any(), anyInt(), any());
		assertThat(families.getValue()).containsExactly(
			sweetgirl.dataops.proposal.enums.ProposalTargetFamily.STORE_NAME,
			sweetgirl.dataops.proposal.enums.ProposalTargetFamily.STORE_PHONE,
			sweetgirl.dataops.proposal.enums.ProposalTargetFamily.STORE_INSTAGRAM,
			sweetgirl.dataops.proposal.enums.ProposalTargetFamily.STORE_ADDRESS,
			sweetgirl.dataops.proposal.enums.ProposalTargetFamily.BUSINESS_HOURS);
	}

	@Test
	void 저장원본_직접_재판정도_메뉴_완전성을_재검증하고_호출자가_넘긴_파싱값은_쓰지_않는다() {
		var snapshot = dailySnapshot(1L, "원본 이름");
		ReflectionTestUtils.setField(snapshot, "payload", snapshot.getPayload().replace("\"menuCount\":1", "\"menuCount\":2"));
		given(placeSnapshotRepository.findById(501L)).willReturn(Optional.of(snapshot));
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(servingReader.findStore(1L)).willReturn(Optional.of(
			new StoreCurrentValues(1L, "옛 이름", "주소", null, "ACTIVE", null, null, null)));

		reconcileService.reconcile(1L, 501L, parsedPlace("임의 전달 이름", null, List.of(), List.of(), null));

		then(proposalCommandService).should().propose(eq(1L), eq("store.name"), any(), contains("원본 이름"), any(), eq(501L), any(), any(), any());
		then(servingReader).should(never()).findMenus(anyLong());
		then(changeProposalRepository).should().expireOutOfGeneration(eq(1L), any(),
			argThat(families -> !families.contains(sweetgirl.dataops.proposal.enums.ProposalTargetFamily.MENU_REMOVAL)),
			any(), anyInt(), any());
	}

	@Test
	void 저장원본_재판정은_실제_메뉴_파싱오류가_있으면_전체를_보류한다() {
		var snapshot = dailySnapshot(1L, "빵집");
		ReflectionTestUtils.setField(snapshot, "payload", snapshot.getPayload().replace("\"price\":\"3000\"", "\"price\":{\"unreadable\":true}"));
		given(placeSnapshotRepository.findById(501L)).willReturn(Optional.of(snapshot));

		reconcileService.reconcile(1L, 501L, parsedPlace("통과할 이름", null, List.of(), List.of(), null));

		then(servingReaderProvider).shouldHaveNoInteractions();
		then(proposalCommandService).shouldHaveNoInteractions();
		then(changeProposalRepository).shouldHaveNoInteractions();
	}

	@Test
	void 영업시간_재판정도_나중_관측과_다른_원본은_보류한다() {
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(placeSnapshotRepository.findTopByStoreIdAndSourceOrderByFetchedAtDesc(1L, SyncSource.NAVER))
			.willReturn(Optional.of(dailySnapshot(1L, "빵집")));
		given(responseCaptureService.isCurrentSnapshot(eq(1L), eq("NAVER"), eq("p-1"), any(), any())).willReturn(false);

		var result = reconcileService.reconcileHoursFromSnapshots(List.of(1L));

		assertThat(result).isEqualTo(new ReconcileService.HoursRejudgeResult(1, 0, 0, 1, 0));
		then(servingReader).shouldHaveNoInteractions();
		then(proposalCommandService).shouldHaveNoInteractions();
		then(changeProposalRepository).shouldHaveNoInteractions();
	}

	@Test
	void 전체_재판정은_진단이_막은_스냅샷을_판정수에_포함하지_않는다() {
		given(placeSnapshotRepository.findDistinctStoreIdsBySource(SyncSource.NAVER)).willReturn(List.of(1L));
		given(placeSnapshotRepository.findTopByStoreIdAndSourceOrderByFetchedAtDesc(1L, SyncSource.NAVER))
			.willReturn(Optional.of(dailySnapshot(1L, null)));

		assertThat(reconcileService.reconcileAllFromSnapshots()).isZero();

		then(proposalCommandService).shouldHaveNoInteractions();
		then(changeProposalRepository).shouldHaveNoInteractions();
	}

	private ParsedMenu naverMenu(String name, String price) {
		return new ParsedMenu(name, price, null, List.of(), null);
	}

	private ParsedMenu baeminMenu(String name, String price, String group) {
		return new ParsedMenu(name, price, null, List.of(), group);
	}

	private ParsedPlace parsedPlace(String name, String phone, List<ParsedMenu> menus,
			List<ParsedMenu> baeminMenus, String instagram) {
		return new ParsedPlace(name, phone, null, null, null, null, null, null, instagram, menus, baeminMenus);
	}

	/** 이미 구체 종류로 분류된 운영 메뉴 — 정책 재판정의 재분류가 끼어들지 않는다 (종류 값 자체는 이 테스트들과 무관) */
	private MenuCurrentValues classifiedMenu(long id, String name, Integer price, String description,
			boolean userAdded) {
		return new MenuCurrentValues(id, name, price, description, userAdded, 30L, "케이크");
	}

	private MenuCurrentValues menuInCategory(long id, String name, boolean userAdded, Long categoryId,
			String category) {
		return new MenuCurrentValues(id, name, 3000, null, userAdded, categoryId, category);
	}

	private void givenStore(String name, String phone, List<MenuCurrentValues> menus) {
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(servingReader.findStore(1L))
			.willReturn(Optional.of(new StoreCurrentValues(1L, name, "주소", phone, "ACTIVE", null, null, null)));
		given(servingReader.findMenus(1L)).willReturn(menus);
		given(provenanceRepository.findByStoreId(1L)).willReturn(List.of());
	}

	@Test
	void 네이버_페이지가_없으면_메뉴_소멸도_무엇도_제안하지_않는다() {
		// 페이지 없음 응답을 판정하면 운영 메뉴 전부가 '네이버에서 사라짐'으로 읽힌다 (09-07 이브런치 소멸 제안 33건)
		reconcileTrusted(1L, 10L, parsedPlace(null, null, List.of(), List.of(), null));

		then(proposalCommandService).shouldHaveNoInteractions();
		then(servingReaderProvider).shouldHaveNoInteractions();
	}

	@Test
	void 네이버_메뉴를_하나도_못_읽으면_소멸을_제안하지_않는다() {
		// 배민 연동 가게는 개편 뒤 홈에 네이버 메뉴가 오지 않는다(09-20 · 96674 76→0개) — 배민 메뉴만 있다
		givenStore("빵집", null, List.of(classifiedMenu(1L, "소금빵", 3000, null, false),
			classifiedMenu(2L, "크루아상", 4000, null, false)));

		reconcileTrusted(1L, 10L,
			parsedPlace("빵집", null, List.of(), List.of(baeminMenu("소금빵", "3000", "빵"), baeminMenu("바게트", "5000", "빵")), null));

		then(proposalCommandService).should(never()).propose(eq(1L), endsWith(".removed"), any(), any(), any(), any(), any(), any(), any());
		then(proposalCommandService).should().propose(eq(1L), startsWith("menu.add."), any(), contains("바게트"), any(), eq(10L), any(), any(), any());
	}

	@Test
	void 명절처럼_날짜가_붙은_요일은_운영_값을_두고_읽힌_요일만_비교한다() {
		// 09-20 실측 — 추석 주에는 "목(9/24)"처럼 날짜가 붙어 오고 파서가 그 요일을 건너뛴다
		givenStore("빵집", null, List.of());
		List<sweetgirl.dataops.snapshot.parser.BusinessHourRow> week = new java.util.ArrayList<>();
		for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY")) {
			week.add(new sweetgirl.dataops.snapshot.parser.BusinessHourRow(day, "09:30", "20:00", false));
		}
		week.add(new sweetgirl.dataops.snapshot.parser.BusinessHourRow("SUNDAY", null, null, true));
		given(servingReader.findBusinessHours(1L)).willReturn(week);
		String holidayWeek = "[{\"day\":\"일\",\"description\":\"정기휴무 (매주 일요일)\",\"businessHours\":null},"
			+ "{\"day\":\"월\",\"businessHours\":{\"start\":\"09:30\",\"end\":\"20:00\"}},"
			+ "{\"day\":\"목(9/24)\",\"description\":\"추석 연휴\",\"businessHours\":{\"start\":\"10:00\",\"end\":\"20:00\"}}]";

		reconcileTrusted(1L, 10L,
			new ParsedPlace("빵집", null, null, null, null, null, null, holidayWeek, null, List.of(), List.of()));
		then(proposalCommandService).should(never()).propose(eq(1L), eq("store.business_hours"), any(), any(), any(), any(), any(), any(), any());

		String mondayChanged = "[{\"day\":\"월\",\"businessHours\":{\"start\":\"10:00\",\"end\":\"20:00\"}},"
			+ "{\"day\":\"목(9/24)\",\"description\":\"추석 연휴\",\"businessHours\":{\"start\":\"10:00\",\"end\":\"20:00\"}}]";
		reconcileTrusted(1L, 11L,
			new ParsedPlace("빵집", null, null, null, null, null, null, mondayChanged, null, List.of(), List.of()));
		ArgumentCaptor<String> newValue = ArgumentCaptor.forClass(String.class);
		then(proposalCommandService).should().propose(eq(1L), eq("store.business_hours"), any(), newValue.capture(), any(), eq(11L), any(), any(), any());
		assertThat(newValue.getValue()).contains("\"open\":\"10:00\"").contains("THURSDAY").contains("SUNDAY");
	}

	/** 저장된 스냅샷 원문 — 영업시간은 "매일" 한 줄, 메뉴·인스타는 운영과 달라도 영업시간 재판정은 보지 않는다 */
	private sweetgirl.dataops.snapshot.entity.PlaceSnapshot dailySnapshot(long storeId, String name) {
		String payload = "{\"ROOT_QUERY\":{\"placeDetail({\\\"input\\\":{\\\"id\\\":\\\"p-" + storeId + "\\\"}})\":{\"id\":\"p-" + storeId + "\","
			+ "\"placeMenus\":{\"menuCount\":1,\"items\":[{\"__ref\":\"Menu:1\"}]},"
			+ "\"newBusinessHours\":[{\"businessHours\":[{\"day\":\"매일\",\"businessHours\":{\"start\":\"11:00\",\"end\":\"21:50\"}}]}]}},"
			+ (name == null ? "" : "\"PlaceDetailBase:" + storeId + "\":{\"id\":\"p-" + storeId + "\",\"name\":\"" + name + "\"},")
			+ "\"Menu:1\":{\"id\":\"1\",\"__typename\":\"Menu\",\"name\":\"새 메뉴\",\"price\":\"3000\"}}";
		sweetgirl.dataops.snapshot.entity.PlaceSnapshot snapshot = sweetgirl.dataops.snapshot.entity.PlaceSnapshot.builder()
			.storeId(storeId).source(SyncSource.NAVER).fetchedAt(java.time.LocalDateTime.now()).payload(payload).build();
		ReflectionTestUtils.setField(snapshot, "id", 500L + storeId);
		return snapshot;
	}

	@Test
	void 영업시간_재판정은_영업시간_제안만_만들고_영업시간_묶음만_정리한다() {
		// "매일" 파서 수정(09-27) 뒤 저장 스냅샷으로 다시 판정 — 운영 영업시간이 비어 있던 가게
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(placeSnapshotRepository.findTopByStoreIdAndSourceOrderByFetchedAtDesc(1L, SyncSource.NAVER))
			.willReturn(Optional.of(dailySnapshot(1L, "빵집")));
		given(servingReader.findStore(1L)).willReturn(Optional.of(
			new StoreCurrentValues(1L, "옛 이름", "주소", "02-1", "ACTIVE", null, null, null)));
		given(servingReader.findBusinessHours(1L)).willReturn(List.of());
		given(proposalCommandService.propose(eq(1L), eq("store.business_hours"), any(), any(), any(), any(), any(), any(), any()))
			.willReturn(new sweetgirl.dataops.proposal.dto.response.ChangeProposalResponse(
				77L, 1L, "store.business_hours", "[]", "[..]", sweetgirl.dataops.reconcile.enums.ProvenanceSource.NAVER, 501L,
				sweetgirl.dataops.proposal.enums.ProposalStatus.PENDING, null, null, java.time.LocalDateTime.now()));

		ReconcileService.HoursRejudgeResult result = reconcileService.reconcileHoursFromSnapshots(List.of(1L));

		assertThat(result).isEqualTo(new ReconcileService.HoursRejudgeResult(1, 1, 1, 0, 0));
		ArgumentCaptor<String> newValue = ArgumentCaptor.forClass(String.class);
		then(proposalCommandService).should().propose(eq(1L), eq("store.business_hours"), any(), newValue.capture(),
			any(), eq(501L), any(), any(), any());
		assertThat(newValue.getValue()).contains("MONDAY").contains("SUNDAY").contains("\"close\":\"21:50\"");
		// 이름·메뉴는 운영과 달라도 이 재판정은 보지 않는다
		then(proposalCommandService).should(times(1)).propose(any(), any(), any(), any(), any(), any(), any(), any(), any());
		ArgumentCaptor<java.util.Collection<sweetgirl.dataops.proposal.enums.ProposalTargetFamily>> families =
			ArgumentCaptor.captor();
		then(changeProposalRepository).should().expireOutOfGeneration(eq(1L),
			eq(sweetgirl.dataops.proposal.enums.ProposalProducer.NAVER_SNAPSHOT_RECONCILE), families.capture(),
			argThat(ids -> ids.size() == 1 && ids.contains(77L)), eq(1), any());
		assertThat(families.getValue()).containsExactly(sweetgirl.dataops.proposal.enums.ProposalTargetFamily.BUSINESS_HOURS);
		then(servingReader).should(never()).findMenus(anyLong());
	}

	@Test
	void 영업시간_재판정은_DELETED_가게와_페이지_없는_스냅샷을_건너뛴다() {
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(placeSnapshotRepository.findTopByStoreIdAndSourceOrderByFetchedAtDesc(1L, SyncSource.NAVER))
			.willReturn(Optional.of(dailySnapshot(1L, "빵집")));
		given(placeSnapshotRepository.findTopByStoreIdAndSourceOrderByFetchedAtDesc(2L, SyncSource.NAVER))
			.willReturn(Optional.of(dailySnapshot(2L, null)));
		given(placeSnapshotRepository.findTopByStoreIdAndSourceOrderByFetchedAtDesc(3L, SyncSource.NAVER))
			.willReturn(Optional.empty());
		given(servingReader.findStore(1L)).willReturn(Optional.of(
			new StoreCurrentValues(1L, "빵집", "주소", null, "DELETED", null, null, null)));
		org.mockito.Mockito.lenient().when(servingReader.findStore(2L)).thenReturn(Optional.of(
			new StoreCurrentValues(2L, "빵집", "주소", null, "ACTIVE", null, null, null)));

		ReconcileService.HoursRejudgeResult result = reconcileService.reconcileHoursFromSnapshots(List.of(1L, 2L, 3L));

		assertThat(result).isEqualTo(new ReconcileService.HoursRejudgeResult(3, 0, 0, 3, 0));
		then(proposalCommandService).shouldHaveNoInteractions();
		then(changeProposalRepository).shouldHaveNoInteractions();
	}

	@Test
	void 인스타_핸들이_운영에_없으면_채움_proposal_을_만든다() {
		givenStore("빵집", null, List.of());

		reconcileTrusted(1L, 10L, parsedPlace("빵집", null, List.of(), List.of(), "bbang_gram"));

		then(proposalCommandService).should().propose(
			eq(1L), eq("store.instagram_handle"), any(), contains("bbang_gram"), any(), eq(10L), any(), any(), any());
	}

	@Test
	void 영업시간이_운영_세트와_다르면_proposal_을_만든다() {
		givenStore("빵집", null, List.of());
		String hoursRaw = "[{\"day\":\"월\",\"businessHours\":{\"start\":\"09:00\",\"end\":\"18:00\"}}]";

		reconcileTrusted(1L, 10L,
			new sweetgirl.dataops.snapshot.parser.ParsedPlace("빵집", null, null, null, null, null, null, hoursRaw,
				null, List.of(), List.of()));

		then(proposalCommandService).should().propose(
			eq(1L), eq("store.business_hours"), any(), contains("MONDAY"), any(), eq(10L), any(), any(), any());
	}

	@Test
	void 영업시간이_운영_세트와_같으면_proposal_을_만들지_않는다() {
		givenStore("빵집", null, List.of());
		given(servingReader.findBusinessHours(1L)).willReturn(List.of(
			new sweetgirl.dataops.snapshot.parser.BusinessHourRow("MONDAY", "09:00", "18:00", false)));
		String hoursRaw = "[{\"day\":\"월\",\"businessHours\":{\"start\":\"09:00\",\"end\":\"18:00\"}}]";

		reconcileTrusted(1L, 10L,
			new sweetgirl.dataops.snapshot.parser.ParsedPlace("빵집", null, null, null, null, null, null, hoursRaw,
				null, List.of(), List.of()));

		then(proposalCommandService).should(never()).propose(
			eq(1L), eq("store.business_hours"), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void 좌표_비교가_불가능하면_주소_문자열로_폴백해_proposal_을_만든다() {
		givenStore("빵집", null, List.of()); // 운영 주소 = "주소", 좌표 없음

		reconcileTrusted(1L, 10L,
			new ParsedPlace("빵집", null, "서울 성동구 성수동 1-1", null, null, null, null, null, null,
				List.of(), List.of()));

		then(proposalCommandService).should().propose(
			eq(1L), eq("store.address"), any(), contains("성수동"), any(), eq(10L), any(), any(), any());
	}

	@Test
	void 좌표가_같은_자리면_주소_표기가_달라도_무시한다() {
		// 우리 DB 도로명 vs 네이버 지번 — 좌표가 같으면(≤150m) 표기 차이는 이전이 아니다 (오탐 6,630건 실측)
		givenStoreAt(127.0, 37.5); // 운영 주소 = "주소"

		reconcileTrusted(1L, 10L,
			new ParsedPlace("빵집", null, "완전히 다른 표기의 지번주소", null, 127.0002, 37.5001, null, null, null,
				List.of(), List.of()));

		then(proposalCommandService).should(never()).propose(
			eq(1L), eq("store.address"), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void 이동_proposal_의_반영_주소는_도로명을_우선한다() {
		givenStoreAt(127.0, 37.5);

		reconcileTrusted(1L, 10L,
			new ParsedPlace("빵집", null, "지번주소 1-1", "도로명로 10", 127.003, 37.5, null, null, null,
				List.of(), List.of()));

		then(proposalCommandService).should().propose(
			eq(1L), eq("store.address"), any(), contains("도로명로 10"), any(), eq(10L), any(), any(), any());
	}

	@Test
	void 좌표_이동이_임계값_이하면_지오코딩_오차로_보고_무시한다() {
		givenStoreAt(127.0, 37.5);

		// 경도 +0.0005도 ≈ 44m — 알림 반경과 같은 자릿수라 신호가 아니다
		reconcileTrusted(1L, 10L,
			new ParsedPlace("빵집", null, "주소", null, 127.0005, 37.5, null, null, null,
				List.of(), List.of()));

		then(proposalCommandService).should(never()).propose(
			eq(1L), eq("store.address"), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void 좌표가_임계값_넘게_이동하면_거리를_실어_proposal_을_만든다() {
		givenStoreAt(127.0, 37.5);

		// 경도 +0.003도 ≈ 265m — 이전(移轉) 또는 오매칭 신호
		reconcileTrusted(1L, 10L,
			new ParsedPlace("빵집", null, "주소", null, 127.003, 37.5, null, null, null,
				List.of(), List.of()));

		then(proposalCommandService).should().propose(
			eq(1L), eq("store.address"), any(), contains("distanceM"), any(), eq(10L), any(), any(), any());
	}

	private void givenStoreAt(double longitude, double latitude) {
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(servingReader.findStore(1L)).willReturn(Optional.of(
			new StoreCurrentValues(1L, "빵집", "주소", null, "ACTIVE", null, longitude, latitude)));
		given(servingReader.findMenus(1L)).willReturn(List.of());
		given(provenanceRepository.findByStoreId(1L)).willReturn(List.of());
	}

	@Test
	void 운영_DB_미설정이면_아무것도_하지_않는다() {
		given(servingReaderProvider.getIfAvailable()).willReturn(null);

		reconcileTrusted(1L, 10L, parsedPlace("빵집", null, List.of(), List.of(), null));

		then(proposalCommandService).shouldHaveNoInteractions();
	}

	@Test
	void 신규_메뉴는_menu_add_proposal_로_수납된다() {
		givenStore("빵집", null, List.of());

		reconcileTrusted(1L, 10L,
			parsedPlace("빵집", null, List.of(naverMenu("소금빵", "2900")), List.of(), null));

		then(proposalCommandService).should().propose(
			eq(1L), eq("menu.add.소금빵"), isNull(),
			eq("{\"name\":\"소금빵\",\"price\":2900,\"description\":null}"), any(), eq(10L), any(), any(), any());
	}

	@Test
	void 운영_이름_끝에_붙은_가격부는_키에서_떼어_같은_메뉴로_본다() {
		givenStore("빵집", null, List.of(
			classifiedMenu(77L, "아카스키 복숭아 산도 변동가격(업주문의)", null, null, false),
			classifiedMenu(78L, "초콜라또 칼도 4,300~6,200", null, null, false)));

		reconcileTrusted(1L, 10L,
			parsedPlace("빵집", null,
				List.of(naverMenu("아카스키 복숭아 산도", ""), naverMenu("초콜라또 칼도", "4300~6200")),
				List.of(), null));

		then(proposalCommandService).shouldHaveNoInteractions();
	}

	@Test
	void 가격부_제거는_공백_뒤_가격_표기만_건드린다() {
		assertThat(ReconcileService.menuKey("플레인소금빵2")).isEqualTo("플레인소금빵2");
		assertThat(ReconcileService.menuKey("휘낭시에 3500원")).isEqualTo("휘낭시에");
		assertThat(ReconcileService.menuKey("한치 시가")).isEqualTo("한치");
		assertThat(ReconcileService.menuKey("2호 케이크 62,000~82,000원")).isEqualTo("2호케이크");
		// 배민 재고 표시 — 방문마다 숫자가 바뀌어도 같은 메뉴다
		assertThat(ReconcileService.menuKey("햄치즈소금빵(남은수량2개)")).isEqualTo("햄치즈소금빵");
		assertThat(ReconcileService.menuKey("버터떡4개입(남은수량5세트)")).isEqualTo("버터떡4개입");
		assertThat(ReconcileService.menuKey("마틸다 초코 케이크 1조각 남았어요(1조각)")).isEqualTo("마틸다초코케이크");
		assertThat(ReconcileService.withoutStock("[생과일]망고 생크림 케이크 1조각 남았어요(1 조각)")).isEqualTo("[생과일]망고 생크림 케이크");
		assertThat(ReconcileService.withoutStock("모둠과일 홀케이크(1호 (지름 약 15cm))")).isEqualTo("모둠과일 홀케이크(1호 (지름 약 15cm))");
	}

	@Test
	void 신규_메뉴라도_음료는_수납하지_않는다() {
		givenStore("빵집", null, List.of());

		reconcileTrusted(1L, 10L,
			parsedPlace("빵집", null, List.of(naverMenu("아메리카노", "1900")),
				List.of(baeminMenu("딸기라떼", "4500", "커피")), null));

		then(proposalCommandService).shouldHaveNoInteractions();
	}

	@Test
	void 배민_단독_메뉴도_신규_후보가_된다() {
		givenStore("빵집", null, List.of());

		reconcileTrusted(1L, 10L,
			parsedPlace("빵집", null, List.of(), List.of(baeminMenu("영양찰떡", "1700", "찰떡류")), null));

		then(proposalCommandService).should().propose(
			eq(1L), eq("menu.add.영양찰떡"), isNull(), contains("영양찰떡"), any(), eq(10L), any(), any(), any());
	}

	@Test
	void 네이버_메뉴_가격_변경은_price_proposal_로_수납된다() {
		givenStore("빵집", null, List.of(classifiedMenu(77L, "소금빵", 2900, null, false)));

		reconcileTrusted(1L, 10L,
			parsedPlace("빵집", null, List.of(naverMenu("소금빵", "3200")), List.of(), null));

		then(proposalCommandService).should().propose(
			eq(1L), eq("menu.77.price"), eq("2900"), eq("3200"), any(), eq(10L), any(), any(), any());
	}

	@Test
	void 네이버_메뉴_설명_변경은_description_proposal_로_수납된다() {
		givenStore("빵집", null, List.of(classifiedMenu(77L, "소금빵", 2900, "옛 설명", false)));

		reconcileTrusted(1L, 10L,
			parsedPlace("빵집", null,
				List.of(new ParsedMenu("소금빵", "2900", "겉은  바삭\n속은 촉촉", List.of(), null)), List.of(), null));

		then(proposalCommandService).should().propose(
			eq(1L), eq("menu.77.description"), eq("\"옛 설명\""), eq("\"겉은 바삭 속은 촉촉\""), any(), eq(10L), any(), any(), any());
	}

	@Test
	void 설명이_공백_정리_후_같거나_네이버_값이_없으면_description_diff_가_아니다() {
		givenStore("빵집", null, List.of(
			classifiedMenu(77L, "소금빵", 2900, "겉은 바삭", false),
			classifiedMenu(78L, "단팥빵", 2500, "옛 설명", false)));

		reconcileTrusted(1L, 10L,
			parsedPlace("빵집", null, List.of(
				new ParsedMenu("소금빵", "2900", " 겉은   바삭 ", List.of(), null),
				new ParsedMenu("단팥빵", "2500", null, List.of(), null)), List.of(), null));

		then(proposalCommandService).shouldHaveNoInteractions();
	}

	@Test
	void 사용자_등록_메뉴의_설명은_네이버_값으로_덮지_않는다() {
		givenStore("빵집", null, List.of(classifiedMenu(77L, "소금빵", 2900, "내가 쓴 설명", true)));

		reconcileTrusted(1L, 10L,
			parsedPlace("빵집", null,
				List.of(new ParsedMenu("소금빵", "2900", "네이버 설명", List.of(), null)), List.of(), null));

		then(proposalCommandService).shouldHaveNoInteractions();
	}

	@Test
	void 배민_가격_변경은_배민_출처의_검토_후보로_남긴다() {
		givenStore("빵집", null, List.of(classifiedMenu(77L, "소금빵", 2900, null, false)));

		reconcileTrusted(1L, 10L,
			parsedPlace("빵집", null, List.of(), List.of(baeminMenu("소금빵", "3500", "대표메뉴")), null));

		then(proposalCommandService).should().propose(eq(1L), eq("menu.77.price"), eq("2900"), eq("3500"),
			eq(sweetgirl.dataops.reconcile.enums.ProvenanceSource.BAEMIN), eq(10L), any(), any(), any());
	}

	@Test void baemin_updates_follow_field_provenance_even_when_naver_is_also_present_and_group_is_null() {
		givenStore("빵집", null, List.of(classifiedMenu(77L, "소금빵", 2900, "옛 설명", false)));
		given(provenanceRepository.findByStoreId(1L)).willReturn(List.of(
			sweetgirl.dataops.reconcile.entity.StoreFieldProvenance.builder().storeId(1L).field("menu.77.price")
				.source(sweetgirl.dataops.reconcile.enums.ProvenanceSource.BAEMIN).build()));
		reconcileService.reconcile(1L, 10L, parsedPlace("빵집", null,
			List.of(naverMenu("소금빵", "3200")),
			List.of(new ParsedMenu("소금빵", "3500", "새 설명", List.of(), null)), null), true, true);
		then(proposalCommandService).should().propose(eq(1L), eq("menu.77.price"), eq("2900"), eq("3500"),
			eq(sweetgirl.dataops.reconcile.enums.ProvenanceSource.BAEMIN), eq(10L), any(), any(), any());
		then(proposalCommandService).should(never()).propose(eq(1L), eq("menu.77.price"), any(), eq("3200"), any(), any(), any(), any(), any());
	}

	@Test void baemin_only_partial_observation_adds_updates_but_never_deletes_or_expires_naver_candidates() {
		givenStore("빵집", null, List.of(classifiedMenu(77L, "소금빵", 2900, "옛 설명", false),
			classifiedMenu(78L, "안 보이는 빵", 5000, null, false)));
		var observed = parsedPlace("빵집", null, List.of(), List.of(
			new ParsedMenu("소금빵", "3500", "새 설명", List.of(), null), baeminMenu("통밀빵", "4000", "빵")), null);
		reconcileService.reconcile(1L, 10L, observed, false, true);
		then(proposalCommandService).should().propose(eq(1L), eq("menu.77.price"), eq("2900"), eq("3500"),
			eq(sweetgirl.dataops.reconcile.enums.ProvenanceSource.BAEMIN), eq(10L), any(), any(), any());
		then(proposalCommandService).should().propose(eq(1L), eq("menu.77.description"), any(), contains("새 설명"),
			eq(sweetgirl.dataops.reconcile.enums.ProvenanceSource.BAEMIN), eq(10L), any(), any(), any());
		then(proposalCommandService).should().propose(eq(1L), eq("menu.add.통밀빵"), isNull(), any(),
			eq(sweetgirl.dataops.reconcile.enums.ProvenanceSource.BAEMIN), eq(10L), any(), any(), any());
		then(proposalCommandService).should(never()).propose(any(), endsWith(".removed"), any(), any(), any(), any(), any(), any(), any());
		then(changeProposalRepository).should().expireMenuSourceOutOfGeneration(eq(1L), any(),
			eq(sweetgirl.dataops.reconcile.enums.ProvenanceSource.BAEMIN), any(), eq(true),
			argThat(targets -> targets.contains("menu.77.price") && !targets.contains("menu.78.price")), any(), anyInt(), any());
		then(changeProposalRepository).should(never()).expireMenuSourceOutOfGeneration(any(), any(),
			eq(sweetgirl.dataops.reconcile.enums.ProvenanceSource.NAVER), any(), anyBoolean(), any(), any(), anyInt(), any());
	}

	@Test void unusable_naver_fields_fall_back_to_verified_baemin_fields() {
		givenStore("빵집", null, List.of(classifiedMenu(77L, "소금빵", 2900, "옛 설명", false)));
		reconcileService.reconcile(1L, 10L, parsedPlace("빵집", null,
			List.of(new ParsedMenu("소금빵", "시가", "  ", List.of(), null)),
			List.of(new ParsedMenu("소금빵", "3500", "배민 설명", List.of(), null)), null), true, true);
		then(proposalCommandService).should().propose(eq(1L), eq("menu.77.price"), eq("2900"), eq("3500"),
			eq(sweetgirl.dataops.reconcile.enums.ProvenanceSource.BAEMIN), eq(10L), any(), any(), any());
		then(proposalCommandService).should().propose(eq(1L), eq("menu.77.description"), any(), contains("배민 설명"),
			eq(sweetgirl.dataops.reconcile.enums.ProvenanceSource.BAEMIN), eq(10L), any(), any(), any());
		then(changeProposalRepository).should().expireMenuSourceOutOfGeneration(eq(1L), any(),
			eq(sweetgirl.dataops.reconcile.enums.ProvenanceSource.BAEMIN), any(), eq(true),
			argThat(targets -> targets.containsAll(List.of("menu.77.price", "menu.77.description"))), any(), anyInt(), any());
	}

	@Test void unselected_baemin_fields_and_user_descriptions_are_not_expired() {
		givenStore("빵집", null, List.of(classifiedMenu(77L, "소금빵", 2900, "기존 설명", false),
			classifiedMenu(78L, "통밀빵", 4000, "사용자 설명", true)));
		reconcileService.reconcile(1L, 10L, parsedPlace("빵집", null,
			List.of(new ParsedMenu("소금빵", "2900", "기존 설명", List.of(), null)),
			List.of(new ParsedMenu("소금빵", "3500", "배민 설명", List.of(), null),
				new ParsedMenu("통밀빵", "4000", "바뀐 설명", List.of(), null)), null), true, true);
		then(changeProposalRepository).should().expireMenuSourceOutOfGeneration(eq(1L), any(),
			eq(sweetgirl.dataops.reconcile.enums.ProvenanceSource.BAEMIN), any(), eq(true),
			argThat(targets -> targets.equals(java.util.Set.of("menu.78.price"))), any(), anyInt(), any());
	}

	@Test void unusable_preferred_baemin_fields_fall_back_without_expiring_baemin_candidates() {
		givenStore("빵집", null, List.of(classifiedMenu(77L, "소금빵", 2900, "옛 설명", false)));
		given(provenanceRepository.findByStoreId(1L)).willReturn(List.of(
			sweetgirl.dataops.reconcile.entity.StoreFieldProvenance.builder().storeId(1L).field("menu.77")
				.source(sweetgirl.dataops.reconcile.enums.ProvenanceSource.BAEMIN).build()));
		reconcileService.reconcile(1L, 10L, parsedPlace("빵집", null,
			List.of(new ParsedMenu("소금빵", "3200", "네이버 설명", List.of(), null)),
			List.of(new ParsedMenu("소금빵", "999999999999999", null, List.of(), null)), null), true, true);
		then(proposalCommandService).should().propose(eq(1L), eq("menu.77.price"), any(), eq("3200"),
			eq(sweetgirl.dataops.reconcile.enums.ProvenanceSource.NAVER), eq(10L), any(), any(), any());
		then(proposalCommandService).should().propose(eq(1L), eq("menu.77.description"), any(), contains("네이버 설명"),
			eq(sweetgirl.dataops.reconcile.enums.ProvenanceSource.NAVER), eq(10L), any(), any(), any());
		then(changeProposalRepository).should(never()).expireMenuSourceOutOfGeneration(any(), any(),
			eq(sweetgirl.dataops.reconcile.enums.ProvenanceSource.BAEMIN), any(), anyBoolean(), any(), any(), anyInt(), any());
	}

	@Test void missing_baemin_values_do_not_expire_pending_field_candidates() {
		givenStore("빵집", null, List.of(classifiedMenu(77L, "소금빵", 2900, "옛 설명", false)));
		reconcileService.reconcile(1L, 10L, parsedPlace("빵집", null, List.of(),
			List.of(new ParsedMenu("소금빵", null, null, List.of(), null)), null), false, true);
		then(proposalCommandService).shouldHaveNoInteractions();
		then(changeProposalRepository).should(never()).expireMenuSourceOutOfGeneration(any(), any(),
			any(), any(), anyBoolean(), any(), any(), anyInt(), any());
	}

	@Test void absence_from_naver_never_removes_menus_with_baemin_row_or_field_provenance() {
		givenStore("빵집", null, List.of(classifiedMenu(77L, "소금빵", 2900, null, false),
			classifiedMenu(78L, "통밀빵", 5000, null, false)));
		given(provenanceRepository.findByStoreId(1L)).willReturn(List.of(
			sweetgirl.dataops.reconcile.entity.StoreFieldProvenance.builder().storeId(1L).field("menu.77")
				.source(sweetgirl.dataops.reconcile.enums.ProvenanceSource.BAEMIN).build(),
			sweetgirl.dataops.reconcile.entity.StoreFieldProvenance.builder().storeId(1L).field("menu.78.price")
				.source(sweetgirl.dataops.reconcile.enums.ProvenanceSource.BAEMIN).build()));
		reconcileService.reconcile(1L, 10L, parsedPlace("빵집", null,
			List.of(naverMenu("새 빵", "3000")), List.of(), null), true, false);
		then(proposalCommandService).should(never()).propose(any(), endsWith(".removed"), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void 사라진_메뉴는_removed_proposal_로_수납된다() {
		givenStore("빵집", null, List.of(classifiedMenu(77L, "옛날빵", 2000, null, false)));

		// 다른 네이버 메뉴는 읽혔는데 옛날빵만 없다 — 메뉴를 하나도 못 읽은 경우는 소멸로 보지 않는다(아래 별도 테스트)
		reconcileTrusted(1L, 10L, parsedPlace("빵집", null, List.of(naverMenu("소금빵", "3000")), List.of(), null));

		then(proposalCommandService).should().propose(
			eq(1L), eq("menu.77.removed"), contains("옛날빵"), isNull(), any(), eq(10L), any(), any(), any());
	}

	@Test
	void 가게명이_정규화_후_같으면_diff_가_아니다() {
		givenStore("카페 로엘", "02-111-2222", List.of());

		reconcileTrusted(1L, 10L, parsedPlace("카페로엘", "02-111-2222", List.of(), List.of(), null));

		then(proposalCommandService).shouldHaveNoInteractions();
	}

	@Test
	void 가게명_변경은_store_name_proposal_로_수납된다() {
		givenStore("옛날이름", null, List.of());

		reconcileTrusted(1L, 10L, parsedPlace("새이름", null, List.of(), List.of(), null));

		then(proposalCommandService).should().propose(
			eq(1L), eq("store.name"), eq("\"옛날이름\""), eq("\"새이름\""), any(), eq(10L), any(), any(), any());
	}

	@Test
	void 인스타_핸들은_스케줄_없이_sync_state_에_저장된다() {
		givenStore("빵집", null, List.of());
		given(storeSyncStateRepository.findByStoreIdAndSource(1L, SyncSource.INSTAGRAM))
			.willReturn(Optional.empty());
		given(storeSyncStateRepository.save(any(StoreSyncState.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		reconcileTrusted(1L, 10L, parsedPlace("빵집", null, List.of(), List.of(), "bbang_gram"));

		ArgumentCaptor<StoreSyncState> captor = ArgumentCaptor.forClass(StoreSyncState.class);
		then(storeSyncStateRepository).should().save(captor.capture());
		assertThat(captor.getValue().getExternalId()).isEqualTo("bbang_gram");
		assertThat(captor.getValue().getNextDueAt()).isNull();
	}

	// ---- 기존 메뉴 정책 재판정 (2026-09-19) — 제외 필터·종류 분류를 이미 있는 메뉴에 다시 적용해 제안한다 ----

	@Test
	void 제외_필터에_걸리는_기존_메뉴는_네이버에_있어도_reason_을_실은_removed_proposal_로_수납된다() {
		givenStore("빵집", null, List.of(new MenuCurrentValues(77L, "아메리카노", 1900, null, false, 2L, "기타")));

		reconcileTrusted(1L, 10L,
			parsedPlace("빵집", null, List.of(naverMenu("아메리카노", "1900")), List.of(), null));

		then(proposalCommandService).should().propose(eq(1L), eq("menu.77.removed"),
			eq("{\"name\":\"아메리카노\",\"price\":1900,\"description\":null,\"category\":\"기타\",\"reason\":\"제외 필터\"}"),
			isNull(), any(), eq(10L), any(), any(), any());
		then(proposalCommandService).shouldHaveNoMoreInteractions();
	}

	@Test
	void 네이버에서_사라졌고_제외_필터에도_걸리면_removed_는_reason_을_실어_한_번만_제안한다() {
		givenStore("빵집", null, List.of(new MenuCurrentValues(77L, "유자차", 4000, null, false, 1L, "빵")));

		reconcileTrusted(1L, 10L, parsedPlace("빵집", null, List.of(), List.of(), null));

		then(proposalCommandService).should(times(1)).propose(
			eq(1L), eq("menu.77.removed"), contains("\"reason\":\"제외 필터\""), isNull(), any(), eq(10L), any(), any(), any());
		then(proposalCommandService).shouldHaveNoMoreInteractions();
	}

	@Test
	void 사용자_등록_메뉴는_제외_필터에_걸려도_재분류_대상이어도_정책_제안을_하지_않는다() {
		givenStore("빵집", null, List.of(
			new MenuCurrentValues(77L, "아메리카노", 1900, null, true, null, null),
			menuInCategory(78L, "앙버터 소금빵", true, 1L, "빵")));

		reconcileTrusted(1L, 10L, parsedPlace("빵집", null,
			List.of(naverMenu("아메리카노", "1900"), naverMenu("앙버터 소금빵", "3000")), List.of(), null));

		then(proposalCommandService).shouldHaveNoInteractions();
	}

	@Test
	void 빵_기타_NULL_종류의_메뉴가_이제_구체_종류로_분류되면_category_proposal_로_수납된다() {
		givenStore("빵집", null, List.of(
			menuInCategory(77L, "앙버터 소금빵", false, 1L, "빵"),
			menuInCategory(78L, "호두과자 10개입", false, 2L, "기타"),
			menuInCategory(79L, "옛날 카스테라", false, null, null)));

		reconcileTrusted(1L, 10L, parsedPlace("빵집", null, List.of(
			naverMenu("앙버터 소금빵", "3000"), naverMenu("호두과자 10개입", "3000"), naverMenu("옛날 카스테라", "3000")),
			List.of(), null));

		then(proposalCommandService).should().propose(eq(1L), eq("menu.77.category"),
			eq("{\"name\":\"앙버터 소금빵\",\"category\":\"빵\",\"categoryId\":1}"), eq("{\"category\":\"소금빵\"}"),
			any(), eq(10L), any(), any(), any());
		then(proposalCommandService).should().propose(eq(1L), eq("menu.78.category"),
			eq("{\"name\":\"호두과자 10개입\",\"category\":\"기타\",\"categoryId\":2}"), eq("{\"category\":\"호두과자\"}"),
			any(), eq(10L), any(), any(), any());
		then(proposalCommandService).should().propose(eq(1L), eq("menu.79.category"),
			eq("{\"name\":\"옛날 카스테라\",\"category\":null,\"categoryId\":null}"), eq("{\"category\":\"카스테라\"}"),
			any(), eq(10L), any(), any(), any());
		then(proposalCommandService).shouldHaveNoMoreInteractions();
	}

	@Test
	void 구체_종류로_이미_분류된_메뉴는_분류기가_달리_봐도_재분류하지_않는다() {
		// AI·어드민이 고른 구체 종류일 수 있다 — 재분류는 뭉뚱그린 종류('빵'·'기타'·NULL)만
		givenStore("빵집", null, List.of(menuInCategory(77L, "생크림 소금빵", false, 30L, "케이크")));

		reconcileTrusted(1L, 10L,
			parsedPlace("빵집", null, List.of(naverMenu("생크림 소금빵", "3000")), List.of(), null));

		then(proposalCommandService).shouldHaveNoInteractions();
	}

	@Test
	void 분류기가_종류를_못_정하면_재분류하지_않는다() {
		// 매핑 없음 → null. '빵'에서 미분류(NULL)로 되돌리는 제안도 내지 않는다
		givenStore("빵집", null, List.of(
			menuInCategory(77L, "세레니티", false, 1L, "빵"),
			menuInCategory(78L, "상투과자", false, null, null)));

		reconcileTrusted(1L, 10L, parsedPlace("빵집", null,
			List.of(naverMenu("세레니티", "3000"), naverMenu("상투과자", "3000")), List.of(), null));

		then(proposalCommandService).shouldHaveNoInteractions();
	}

	@Test
	void 분류기가_판정을_믿기_어려우면_doubt_재분류하지_않는다() {
		// 끝단어만 보면 키슈→슈 · 인절미빵→떡 · 닭강정→한과 — classify 가 null 을 줘 미분류 큐(AI)에 남는다
		// (규칙 자체는 MenuCategoryClassifierTest, menu_reclassify.py 의 검토 대기와 같은 식)
		givenStore("빵집", null, List.of(
			menuInCategory(77L, "키슈", false, 1L, "빵"),
			menuInCategory(78L, "인절미빵", false, 2L, "기타"),
			menuInCategory(79L, "닭강정", false, null, null)));

		reconcileTrusted(1L, 10L, parsedPlace("빵집", null,
			List.of(naverMenu("키슈", "3000"), naverMenu("인절미빵", "3000"), naverMenu("닭강정", "3000")),
			List.of(), null));

		then(proposalCommandService).shouldHaveNoInteractions();
	}

	@Test
	void 제외_필터에_걸리면_재분류보다_소멸이_우선이다() {
		// 분류기는 '요거트'로 보지만 제품 타입은 스무디(음료) — 재분류하지 않고 지우자고만 한다
		givenStore("빵집", null, List.of(menuInCategory(77L, "요거트스무디", false, null, null)));

		reconcileTrusted(1L, 10L,
			parsedPlace("빵집", null, List.of(naverMenu("요거트스무디", "3000")), List.of(), null));

		then(proposalCommandService).should().propose(
			eq(1L), eq("menu.77.removed"), contains("제외 필터"), isNull(), any(), eq(10L), any(), any(), any());
		then(proposalCommandService).should(never()).propose(
			anyLong(), eq("menu.77.category"), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void 정리로_삭제_표시된_식사_메뉴는_네이버에_남아_있어도_다시_들어오지_않는다() {
		// 09-19 정리(식사 26,510건)로 지운 메뉴는 운영 현재값에 없다(deleted_at) — 네이버에 남아 있으면 신규 후보가 되지만
		// 같은 제외 필터(식사 확장)가 menu.add 를 막는다. 소멸·재분류 대상도 아니므로 아무 제안도 없다
		givenStore("빵집", null, List.of());

		reconcileTrusted(1L, 10L, parsedPlace("빵집", null,
			List.of(naverMenu("리코타 샐러드", "12000"), naverMenu("베이컨 까르보나라", "15000")), List.of(), null));

		then(proposalCommandService).shouldHaveNoInteractions();
	}

	@Test
	void 네이버에서_사라진_메뉴도_재분류는_따로_제안하고_소멸_제안은_원래_모양이다() {
		// 정책 재판정은 운영 메뉴 행만 본다 — 소멸(네이버 신호)과 재분류(정책)는 서로 다른 target 의 독립 판단
		givenStore("빵집", null, List.of(new MenuCurrentValues(77L, "옛날 카스테라", 8000, null, false, 1L, "빵")));

		reconcileTrusted(1L, 10L, parsedPlace("빵집", null, List.of(naverMenu("소금빵", "3000")), List.of(), null));

		then(proposalCommandService).should().propose(eq(1L), eq("menu.77.removed"),
			eq("{\"name\":\"옛날 카스테라\",\"price\":8000,\"description\":null}"), isNull(), any(), eq(10L), any(), any(), any());
		then(proposalCommandService).should().propose(
			eq(1L), eq("menu.77.category"), any(), eq("{\"category\":\"카스테라\"}"), any(), eq(10L), any(), any(), any());
	}
}
