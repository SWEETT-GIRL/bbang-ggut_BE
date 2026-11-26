package sweetgirl.dataops.proposal.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.beans.factory.ObjectProvider;

import sweetgirl.dataops.proposal.code.ProposalErrorCode;
import sweetgirl.dataops.proposal.dto.response.ChangeProposalResponse;
import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.exception.ProposalException;
import sweetgirl.dataops.serving.dto.MenuUpsertResult;
import sweetgirl.dataops.proposal.enums.ApplyOutcomeReason;
import sweetgirl.dataops.proposal.enums.ProposalProducer;
import sweetgirl.dataops.proposal.policy.LocationProposalGate;
import sweetgirl.dataops.proposal.policy.MenuRemovalProposalGate;
import sweetgirl.dataops.proposal.repository.ChangeProposalRepository;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.reconcile.policy.MenuCategoryClassifier;
import sweetgirl.dataops.serving.client.ServingWriter;
import sweetgirl.dataops.serving.exception.ServingException;
import sweetgirl.dataops.serving.service.ApplyLogCommandService;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ProposalCommandServiceTest {

	@Mock
	private ChangeProposalRepository changeProposalRepository;
	@Mock
	private ApplyLogCommandService applyLogCommandService;
	@Mock
	private ObjectProvider<ServingWriter> servingWriterProvider;
	@Mock
	private ObjectProvider<sweetgirl.dataops.serving.client.ServingReader> servingReaderProvider;
	@Mock
	private sweetgirl.dataops.region.repository.RegionResolutionRepository regionResolutionRepository;

	/** 승인 게이트가 인정하는 근거의 버전 — 설정(dataops.region.*)으로 주입된다 */
	private static final String REF = "ref-now";
	private static final String PROJ = "proj-now";
	private ServingWriter servingWriter;
	@Mock
	private ProposalApplyStateService applyStateService;

	@Mock
	private MenuRemovalProposalGate menuRemovalProposalGate;
	@Mock
	private sweetgirl.dataops.snapshot.concurrency.StoreObservationBarrier observationBarrier;
	@Mock
	private sweetgirl.dataops.snapshot.concurrency.StoreObservationBarrier.ApprovalScope approvalBarrierScope;

	private ProposalCommandService proposalCommandService;

	@BeforeEach
	void setUp() {
		Mockito.lenient().when(observationBarrier.lockApproval(anyLong())).thenReturn(approvalBarrierScope);
		// 조건부 쓰기의 기본 응답은 "1행 반영" — Mockito 의 int 기본값 0 을 그대로 두면
		// 모든 승인이 CAS 충돌(SERVING_VALUE_DRIFTED)로 읽힌다. 0행은 각 테스트에서 명시적으로 스텁한다
		servingWriter = mock(ServingWriter.class, invocation ->
			invocation.getMethod().getReturnType() == int.class
				? 1
				: org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
		proposalCommandService = new ProposalCommandService(
			changeProposalRepository, applyStateService, applyLogCommandService, servingWriterProvider,
			servingReaderProvider, new LocationProposalGate(
				new sweetgirl.dataops.region.service.RegionEvidenceFinder(regionResolutionRepository, REF, PROJ)),
			menuRemovalProposalGate, observationBarrier, new ObjectMapper(), new MenuCategoryClassifier());
	}

	/**
	 * 점유·결과 확정을 실제 엔티티 전이로 흉내낸다 — 승인 경로가 PENDING → APPLYING → 확정 순서를 지키지 않으면
	 * 엔티티 쪽 전이 검사에서 터진다. 테스트마다 쓰는 전이가 달라 lenient 로 둔다.
	 */
	/** 가게 1 의 (lon, lat) 좌표를 지금 버전으로 역산한 근거가 있다 */
	private void givenEvidence(double lon, double lat, String status, String reason,
			String r1, String r2, String r3) {
		given(regionResolutionRepository.findEvidence(eq("STORE"), eq("1"), eq(lon), eq(lat), eq(REF), eq(PROJ), any()))
			.willReturn(List.of(sweetgirl.dataops.region.entity.RegionResolution.of(
				900L, 1L, lon, lat, status, reason, "RESOLVED".equals(status) ? "1120011500" : null,
				REF, PROJ, r1, r2, r3)));
	}

	/** 위치 게이트가 보는 운영 현재 좌표 */
	private void givenServingReader(double lon, double lat) {
		sweetgirl.dataops.serving.client.ServingReader reader =
			mock(sweetgirl.dataops.serving.client.ServingReader.class);
		Mockito.lenient().when(reader.findStore(1L)).thenReturn(Optional.of(
			new sweetgirl.dataops.serving.dto.StoreCurrentValues(
				1L, "가게", "주소", "02-1", "ACTIVE", null, lon, lat)));
		Mockito.lenient().when(servingReaderProvider.getIfAvailable()).thenReturn(reader);
	}

	private UUID stubApplyState(ChangeProposal proposal) {
		UUID operationId = UUID.randomUUID();
		Mockito.lenient().when(applyStateService.claim(5L)).thenAnswer(invocation -> {
			proposal.beginApply(operationId);
			return operationId;
		});
		Mockito.lenient().doAnswer(invocation -> {
			proposal.completeApply(invocation.getArgument(2));
			return null;
		}).when(applyStateService).settleApplied(eq(5L), any(), any());
		Mockito.lenient().doAnswer(invocation -> {
			proposal.failApply(invocation.getArgument(1), invocation.getArgument(2));
			return null;
		}).when(applyStateService).settleFailed(eq(5L), any(), any());
		Mockito.lenient().doAnswer(invocation -> {
			proposal.markApplyUnknown(invocation.getArgument(1), invocation.getArgument(2));
			return null;
		}).when(applyStateService).settleUnknown(eq(5L), any(), any());
		return operationId;
	}

	private ChangeProposal createProposal(String target, String newValue) {
		return createProposal(target, "\"old\"", newValue);
	}

	private ChangeProposal createProposal(String target, String oldValue, String newValue) {
		return ChangeProposal.builder()
			.storeId(1L).target(target).oldValue(oldValue).newValue(newValue)
			.source(ProvenanceSource.NAVER).snapshotId(7L)
			.build();
	}

	@Test void approving_baemin_addition_never_uses_existing_menu_update_path() {
		var proposal = ChangeProposal.builder().storeId(1L).target("menu.add.소금빵").source(ProvenanceSource.BAEMIN)
			.newValue("{\"name\":\"소금빵\",\"price\":3500,\"description\":\"배달 메뉴\"}").build();
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);
		given(servingWriter.upsertMenu(eq(1L), eq("소금빵"), eq(3500), any(), any(), eq(false)))
			.willReturn(MenuUpsertResult.blockedByExistingMenu(77L));
		assertThatThrownBy(() -> proposalCommandService.approve(5L)).isInstanceOf(ProposalException.class);
		then(servingWriter).should(never()).upsertMenu(anyLong(), any(), any(), any(), any());
		then(applyStateService).should(never()).settleApplied(any(), any(), any());
	}

	@Test void another_menu_source_keeps_the_pending_candidate_and_records_review_conflict() {
		ChangeProposal existing = createProposal("menu.77.price", "2900", "3100");
		given(changeProposalRepository.findFirstByStoreIdAndTargetAndStatusIn(eq(1L), eq("menu.77.price"), any()))
			.willReturn(Optional.of(existing));
		var response = proposalCommandService.propose(1L, "menu.77.price", "2900", "3500", ProvenanceSource.BAEMIN, 8L);
		assertThat(response.source()).isEqualTo(ProvenanceSource.NAVER);
		assertThat(existing.getNewValue()).isEqualTo("3100");
		assertThat(existing.getStatus()).isEqualTo(ProposalStatus.PENDING);
		assertThat(existing.getReviewNote()).contains("MENU_SOURCE_CONFLICT", "BAEMIN");
		then(changeProposalRepository).should(never()).save(any());
	}

	@Test void menu_source_review_distinguishes_same_unknown_and_conflicting_sources_without_applying() {
		var proposal = ChangeProposal.builder().storeId(1L).target("menu.77.price").oldValue("2900")
			.newValue("3500").source(ProvenanceSource.BAEMIN).build();
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		proposalCommandService.noteMenuSourceComparison(5L, ProvenanceSource.BAEMIN, ProvenanceSource.BAEMIN);
		assertThat(proposal.getReviewNote()).contains("MENU_SAME_SOURCE_UPDATE", "배달 가격");
		proposalCommandService.noteMenuSourceComparison(5L, null, ProvenanceSource.BAEMIN);
		assertThat(proposal.getReviewNote()).contains("MENU_SOURCE_UNKNOWN");
		proposalCommandService.noteMenuSourceComparison(5L, ProvenanceSource.USER, ProvenanceSource.BAEMIN);
		assertThat(proposal.getReviewNote()).contains("MENU_SOURCE_CONFLICT", "USER");
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PENDING);
		then(servingWriterProvider).shouldHaveNoInteractions();
	}

	@Test void missing_source_review_proposal_is_rejected() {
		assertThatThrownBy(() -> proposalCommandService.noteMenuSourceComparison(5L, null, ProvenanceSource.BAEMIN))
			.isInstanceOf(sweetgirl.dataops.proposal.exception.ProposalException.class);
	}

	@Test
	void propose_같은_대상의_동일_제안은_스킵한다() {
		ChangeProposal existing = createProposal("store.phone", "\"02-1\"");
		given(changeProposalRepository.findFirstByStoreIdAndTargetAndStatusIn(
			eq(1L), eq("store.phone"), any())).willReturn(Optional.of(existing));

		proposalCommandService.propose(1L, "store.phone", "\"old\"", "\"02-1\"", ProvenanceSource.NAVER, 7L);

		then(changeProposalRepository).should(never()).save(any());
		assertThat(existing.getStatus()).isEqualTo(ProposalStatus.PENDING);
	}

	@Test
	void propose_같은_대상에_다른_값이_오면_기존을_supersede_한다() {
		ChangeProposal existing = createProposal("store.phone", "\"02-1\"");
		given(changeProposalRepository.findFirstByStoreIdAndTargetAndStatusIn(
			eq(1L), eq("store.phone"), any())).willReturn(Optional.of(existing));
		given(changeProposalRepository.save(any(ChangeProposal.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		proposalCommandService.propose(1L, "store.phone", "\"old\"", "\"02-2\"", ProvenanceSource.NAVER, 8L);

		assertThat(existing.getStatus()).isEqualTo(ProposalStatus.SUPERSEDED);
		// 옛 PENDING 을 내보낸 뒤에 새 PENDING 을 넣어야 uq_proposal_pending 에 걸리지 않는다
		InOrder inOrder = inOrder(changeProposalRepository);
		inOrder.verify(changeProposalRepository).flush();
		inOrder.verify(changeProposalRepository).save(any(ChangeProposal.class));
	}

	@Test
	void propose_같은_값으로_거절된_이력이_있으면_재상정하지_않는다() {
		given(changeProposalRepository.existsByStoreIdAndTargetAndNewValueAndStatus(
			1L, "store.name", "\"새이름\"", ProposalStatus.REJECTED)).willReturn(true);

		ChangeProposalResponse response = proposalCommandService.propose(
			1L, "store.name", "\"old\"", "\"새이름\"", ProvenanceSource.NAVER, 7L);

		assertThat(response).isNull();
		then(changeProposalRepository).should(never()).save(any());
	}

	@Test
	void approve_는_점유_선기록_운영_DML_순서를_지킨다() {
		ChangeProposal proposal = createProposal("store.phone", "\"02-333-4444\"");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);

		ChangeProposalResponse response = proposalCommandService.approve(5L);

		// 점유가 운영 쓰기보다 먼저여야 한다 — 뒤집히면 중복 승인 두 요청이 모두 운영에 쓴다
		InOrder inOrder = inOrder(applyStateService, applyLogCommandService, servingWriter);
		inOrder.verify(applyStateService).claim(5L);
		inOrder.verify(applyLogCommandService).recordBeforeApply(
			eq(1L), eq("store.phone"), any(), any(), any(), any(), any());
		inOrder.verify(servingWriter).updateStorePhone(1L, "old", "02-333-4444");
		inOrder.verify(applyStateService).settleApplied(5L, "phone", null);
		assertThat(response.status()).isEqualTo(ProposalStatus.APPROVED);
		then(applyStateService).should().settleApplied(5L, "phone", null);
	}

	@Test
	void approve_menu_add_는_생성된_menu_id_로_provenance_를_남긴다() {
		ChangeProposal proposal = createProposal("menu.add.소금빵",
			"{\"name\": \"소금빵\", \"price\": 2900, \"description\": null}");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);
		given(servingWriter.upsertMenu(1L, "소금빵", 2900, null, "소금빵"))
			.willReturn(MenuUpsertResult.inserted(77L));

		ChangeProposalResponse response = proposalCommandService.approve(5L);

		// 카테고리는 분류기(실객체)가 이름에서 해석해 전달한다 — 소금빵 → 소금빵
		then(servingWriter).should().upsertMenu(1L, "소금빵", 2900, null, "소금빵");
		then(applyStateService).should().settleApplied(eq(5L), eq("menu.77"), any());
		assertThat(response.reviewNote()).isNull();
	}

	@Test
	void approve_menu_add_가_기존_메뉴와_겹치면_갱신으로_흡수하고_note_를_남긴다() {
		ChangeProposal proposal = createProposal("menu.add.생식빵",
			"{\"name\": \"生食パン(생식빵)\", \"price\": 5500, \"description\": \"촉촉\"}");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);
		given(servingWriter.upsertMenu(eq(1L), eq("生食パン(생식빵)"), eq(5500), eq("촉촉"), any()))
			.willReturn(MenuUpsertResult.updated(88L, 5000, null));

		ChangeProposalResponse response = proposalCommandService.approve(5L);

		assertThat(response.status()).isEqualTo(ProposalStatus.APPROVED);
		assertThat(response.reviewNote()).contains("기존 메뉴 #88").contains("5000→5500");
		then(applyStateService).should().settleApplied(eq(5L), eq("menu.88"), any());
	}

	@Test
	void approve_menu_add_가_사용자_등록_메뉴와_겹치면_예외이고_FAILED_로_확정된다() {
		ChangeProposal proposal = createProposal("menu.add.소금빵",
			"{\"name\": \"소금빵\", \"price\": 2900, \"description\": null}");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);
		given(servingWriter.upsertMenu(1L, "소금빵", 2900, null, "소금빵"))
			.willReturn(MenuUpsertResult.blockedByUserMenu(99L));

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class)
			.hasMessageContaining("menu.99");
		// 운영에 아무것도 쓰지 않았으므로 FAILED — PENDING 으로 남기면 다음 승인이 같은 충돌을 다시 시도한다
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.FAILED);
		then(applyStateService).should()
			.settleFailed(eq(5L), eq(ApplyOutcomeReason.MENU_CONFLICT.name()), any());
		then(applyStateService).should(never()).settleApplied(any(), any(), any());
	}

	@Test
	void approve_menu_add_가_이름_다른_별개_상품과_운영_키가_겹치면_예외이고_FAILED_로_확정된다() {
		ChangeProposal proposal = createProposal("menu.add.답례세트",
			"{\"name\": \"✔️답례세트③\", \"price\": 5000, \"description\": null}");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);
		given(servingWriter.upsertMenu(eq(1L), eq("✔️답례세트③"), eq(5000), isNull(), any()))
			.willReturn(MenuUpsertResult.blockedByDistinctName(66L));

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class)
			.hasMessageContaining("menu.66");
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.FAILED);
		then(applyStateService).should()
			.settleFailed(eq(5L), eq(ApplyOutcomeReason.MENU_CONFLICT.name()), any());
		then(applyStateService).should(never()).settleApplied(any(), any(), any());
	}

	@Test
	void approve_address_는_새_좌표의_법정동_근거로_주소_좌표_지역을_한_번에_쓴다() {
		// 재판정이 만드는 실제 주소 제안에는 지역이 없다 — 지역은 region_resolution 근거에서 온다 (v3 P4-4)
		ChangeProposal proposal = createProposal("store.address",
			"{\"address\": \"서울 성동구 성수동 9-9\", \"lon\": 127.04, \"lat\": 37.53}",
			"{\"address\": \"서울 성동구 성수이로 1\", \"lon\": 127.05, \"lat\": 37.54, \"distanceM\": 265}");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		givenEvidence(127.05, 37.54, "RESOLVED", null, "서울", "성동구", "성수동2가");
		stubApplyState(proposal);

		proposalCommandService.approve(5L);

		// 주소·좌표·지역이 한 번의 호출로 나간다 — 나눠 쓰면 중간 실패가 반쪽 반영을 남긴다
		then(servingWriter).should().updateStoreAddress(
			1L, "서울 성동구 성수동 9-9", "서울 성동구 성수이로 1", 127.05, 37.54, "서울", "성동구", "성수동2가");
		then(servingWriter).should(never()).updateStoreRegion(anyLong(), any(), any(), any(), any(), any(), any());
		// apply_log 는 실제로 쓴 지역과 근거를 담는다 — 제안 JSON 그대로면 로그가 운영 값과 달라진다
		then(applyLogCommandService).should().recordBeforeApply(eq(1L), eq("store.address"), any(),
			argThat(logged -> logged.contains("\"region_3rd\":\"성수동2가\"") && logged.contains("\"bjd_code\":\"1120011500\"")
				&& logged.contains("\"region_resolution_id\":900")), any(), any(), any());
		then(applyStateService).should().settleApplied(eq(5L), eq("address"), argThat(note -> note.contains("#900")));
	}

	@Test
	void approve_address_에_지역_근거가_없으면_보류하고_PENDING_으로_남긴다() {
		// 주소·좌표만 옮기면 지역이 옛 동네에 남아 가게가 두 동네에 동시에 나타난다
		ChangeProposal proposal = createProposal("store.address",
			"{\"address\": \"서울 성동구 성수일로 12길 19-1\", \"lon\": 127.05, \"lat\": 37.54}");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		given(regionResolutionRepository.findEvidence(any(), any(), anyDouble(), anyDouble(), any(), any(), any()))
			.willReturn(List.of());

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class)
			.extracting("errorCode").isEqualTo(ProposalErrorCode.LOCATION_EVIDENCE_REQUIRED);

		// 보류는 실패가 아니다 — 근거가 붙으면 그때 승인한다
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PENDING);
		then(applyStateService).should(never()).claim(any());
		then(servingWriter).shouldHaveNoInteractions();
	}

	@Test
	void approve_address_는_지금_버전의_근거만_인정한다() {
		// 옛 기준표로 만든 근거만 있으면 지금 버전 조회는 빈 결과다 — 버전을 조건으로 넘기는지 본다
		ChangeProposal proposal = createProposal("store.address",
			"{\"address\": \"광주 광산구 월계동 1\", \"lon\": 126.84, \"lat\": 35.21}");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		given(regionResolutionRepository.findEvidence(eq("STORE"), eq("1"), eq(126.84), eq(35.21), eq(REF), eq(PROJ), any()))
			.willReturn(List.of());

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class)
			.hasMessageContaining(REF);
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PENDING);
	}

	@Test
	void approve_address_의_최신_근거가_미해소면_사유를_실어_보류한다() {
		ChangeProposal proposal = createProposal("store.address",
			"{\"address\": \"어딘가\", \"lon\": 127.05, \"lat\": 37.54}");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		givenEvidence(127.05, 37.54, "UNRESOLVED", "CODE_NOT_IN_REFERENCE", null, null, null);

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class)
			.hasMessageContaining("CODE_NOT_IN_REFERENCE");
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PENDING);
	}

	@Test
	void approve_address_가_실은_지역이_근거와_다르면_보류한다() {
		// 원천 간 충돌 — 어느 쪽이 맞는지 자동으로 정하지 않는다
		ChangeProposal proposal = createProposal("store.address",
			"{\"address\": \"성수\", \"lon\": 127.05, \"lat\": 37.54, "
				+ "\"region_1st\": \"서울\", \"region_2nd\": \"성동구\", \"region_3rd\": \"성수동\"}");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		givenEvidence(127.05, 37.54, "RESOLVED", null, "서울", "성동구", "성수동2가");

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class)
			.hasMessageContaining("법정동 근거와 다릅니다");
		then(servingWriter).shouldHaveNoInteractions();
	}

	@Test
	void approve_address_에_좌표가_없으면_보류한다() {
		ChangeProposal proposal = createProposal("store.address", "{\"address\": \"주소만\"}");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class)
			.hasMessageContaining("좌표가 없는");
		then(regionResolutionRepository).shouldHaveNoInteractions();
	}

	@Test
	void approve_region_은_현재_좌표의_근거와_같을_때만_근거_값을_쓴다() {
		ChangeProposal proposal = createProposal("store.region",
			"{\"region_1st\": \"서울\", \"region_2nd\": \"광진구\", \"region_3rd\": \"구의동\"}",
			"{\"region_1st\": \"서울\", \"region_2nd\": \"성동구\", \"region_3rd\": \"성수동2가\"}");
		givenServingReader(127.05, 37.54);
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		givenEvidence(127.05, 37.54, "RESOLVED", null, "서울", "성동구", "성수동2가");
		stubApplyState(proposal);

		proposalCommandService.approve(5L);

		// 조건은 제안 당시 지역(광진구·구의동), 새 값은 근거의 지역
		then(servingWriter).should().updateStoreRegion(1L, "서울", "광진구", "구의동", "서울", "성동구", "성수동2가");
		then(applyStateService).should().settleApplied(eq(5L), eq("region"), any());
	}

	@Test
	void approve_region_은_제안_이후_가게가_움직여_지금_좌표의_근거가_없으면_보류한다() {
		// 역산에 쓴 좌표와 지금 운영 좌표가 다르면 그 지역 값은 옛 자리의 것이다
		ChangeProposal proposal = createProposal("store.region",
			"{\"region_1st\": \"서울\", \"region_2nd\": \"광진구\", \"region_3rd\": \"구의동\"}",
			"{\"region_1st\": \"서울\", \"region_2nd\": \"성동구\", \"region_3rd\": \"성수동2가\"}");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		givenServingReader(127.09, 37.58);
		given(regionResolutionRepository.findEvidence(eq("STORE"), eq("1"), eq(127.09), eq(37.58), eq(REF), eq(PROJ), any()))
			.willReturn(List.of());

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class)
			.hasMessageContaining("현재 운영 좌표");
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PENDING);
	}

	@Test
	void approve_region_의_제안_지역이_현재_좌표의_근거와_다르면_보류한다() {
		ChangeProposal proposal = createProposal("store.region",
			"{\"region_1st\": \"서울\", \"region_2nd\": \"광진구\", \"region_3rd\": \"구의동\"}",
			"{\"region_1st\": \"서울\", \"region_2nd\": \"성동구\", \"region_3rd\": \"성수동\"}");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		givenServingReader(127.05, 37.54);
		givenEvidence(127.05, 37.54, "RESOLVED", null, "서울", "성동구", "성수동2가");

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class)
			.hasMessageContaining("현재 좌표의 법정동과 다릅니다");
		then(servingWriter).shouldHaveNoInteractions();
	}

	@Test
	void approve_menu_description_은_updateMenuDescription_을_호출한다() {
		ChangeProposal proposal = createProposal("menu.77.description", "\"겉바속촉 소금빵\"");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);

		proposalCommandService.approve(5L);

		then(servingWriter).should().updateMenuDescription(77L, "old", "겉바속촉 소금빵");
		then(applyStateService).should().settleApplied(eq(5L), eq("menu.77.description"), any());
	}

	@Test
	void approve_menu_removed_는_new_value_가_null_이어도_soft_delete_한다() {
		ChangeProposal proposal = ChangeProposal.builder()
			.storeId(1L).target("menu.353765.removed").oldValue("{\"name\": \"암행어사\"}").newValue(null)
			.source(ProvenanceSource.NAVER).snapshotId(7L)
			.build();
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);
		given(menuRemovalProposalGate.handles("menu.353765.removed")).willReturn(true);

		proposalCommandService.approve(5L);

		InOrder order = inOrder(observationBarrier, approvalBarrierScope, menuRemovalProposalGate,
			applyStateService, applyLogCommandService, servingWriter);
		order.verify(observationBarrier).lockApproval(1L);
		order.verify(menuRemovalProposalGate).verify(eq(proposal), isNull());
		order.verify(approvalBarrierScope).assertHeld();
		order.verify(applyStateService).claim(5L);
		order.verify(applyLogCommandService).recordBeforeApply(any(), any(), any(), any(), any(), any(), any());
		order.verify(approvalBarrierScope).assertHeld();
		order.verify(servingWriter).softDeleteMenu(353765L, "암행어사");
		order.verify(applyStateService).settleApplied(eq(5L), any(), any());
		order.verify(approvalBarrierScope).close();
	}

	@Test
	void menu_approval_barrier_contention_holds_before_gate_claim_or_write() {
		ChangeProposal proposal = createProposal("menu.77.removed", "{\"name\":\"소금빵\"}", null);
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		given(menuRemovalProposalGate.handles("menu.77.removed")).willReturn(true);
		given(observationBarrier.lockApproval(1L)).willThrow(new org.springframework.dao.CannotAcquireLockException("busy"));
		assertThatThrownBy(() -> proposalCommandService.approve(5L)).isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
		then(applyStateService).shouldHaveNoInteractions();
		then(applyLogCommandService).shouldHaveNoInteractions();
		then(servingWriter).shouldHaveNoInteractions();
		then(menuRemovalProposalGate).should(never()).verify(any(), any());
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PENDING);
	}

	@Test
	void approve_menu_absence_without_evidence_stays_pending_before_claim_and_apply_log() {
		ChangeProposal proposal = createProposal("menu.77.removed", "{\"name\":\"소금빵\"}", null);
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		given(menuRemovalProposalGate.handles("menu.77.removed")).willReturn(true);
		willThrow(new ProposalException(ProposalErrorCode.MENU_EVIDENCE_REQUIRED, "메뉴 불완전"))
			.given(menuRemovalProposalGate).verify(eq(proposal), isNull());

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class).hasMessageContaining("메뉴 불완전");

		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PENDING);
		then(approvalBarrierScope).should().close();
		then(applyStateService).shouldHaveNoInteractions();
		then(applyLogCommandService).shouldHaveNoInteractions();
		then(servingWriter).shouldHaveNoInteractions();
	}

	private ChangeProposal categoryProposal(String oldValue, String newValue) {
		return ChangeProposal.builder()
			.storeId(1L).target("menu.77.category").oldValue(oldValue).newValue(newValue)
			.source(ProvenanceSource.NAVER).snapshotId(7L)
			.build();
	}

	@Test
	void approve_menu_category_는_제안_당시_종류_id_를_조건으로_바꾸고_provenance_를_남긴다() {
		ChangeProposal proposal = categoryProposal(
			"{\"name\": \"앙버터 소금빵\", \"category\": \"빵\", \"categoryId\": 3}", "{\"category\": \"소금빵\"}");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);
		given(servingWriter.updateMenuCategory(77L, 3L, "소금빵")).willReturn(1);

		ChangeProposalResponse response = proposalCommandService.approve(5L);

		assertThat(response.status()).isEqualTo(ProposalStatus.APPROVED);
		then(applyStateService).should().settleApplied(eq(5L), eq("menu.77.category"), any());
	}

	@Test
	void approve_menu_category_는_미분류_NULL_이었으면_NULL_인지를_조건으로_삼는다() {
		ChangeProposal proposal = categoryProposal(
			"{\"name\": \"옛날 카스테라\", \"category\": null, \"categoryId\": null}", "{\"category\": \"카스테라\"}");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);
		given(servingWriter.updateMenuCategory(77L, null, "카스테라")).willReturn(1);

		proposalCommandService.approve(5L);

		then(servingWriter).should().updateMenuCategory(77L, null, "카스테라");
	}

	@Test
	void approve_menu_category_가_그새_바뀌었으면_예외이고_FAILED_로_확정된다() {
		// AI 스케줄러·어드민이 먼저 분류했거나 메뉴가 지워졌다 — 운영 0행
		ChangeProposal proposal = categoryProposal(
			"{\"name\": \"앙버터 소금빵\", \"category\": \"빵\", \"categoryId\": 3}", "{\"category\": \"소금빵\"}");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);
		given(servingWriter.updateMenuCategory(77L, 3L, "소금빵")).willReturn(0);

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class)
			.hasMessageContaining("menu.77.category")
			.extracting("errorCode").isEqualTo(ProposalErrorCode.MENU_CATEGORY_CHANGED);
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.FAILED);
		then(applyStateService).should()
			.settleFailed(eq(5L), eq(ApplyOutcomeReason.MENU_CATEGORY_CHANGED.name()), any());
		then(applyStateService).should(never()).settleApplied(any(), any(), any());
	}

	@Test
	void approve_정책_소멸_제안도_reason_과_무관하게_soft_delete_한다() {
		ChangeProposal proposal = ChangeProposal.builder()
			.storeId(1L).target("menu.88.removed")
			.oldValue("{\"name\": \"아메리카노\", \"price\": 1900, \"category\": \"기타\", \"reason\": \"제외 필터\"}")
			.newValue(null)
			.source(ProvenanceSource.NAVER).snapshotId(7L)
			.build();
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);

		proposalCommandService.approve(5L);

		then(servingWriter).should().softDeleteMenu(88L, "아메리카노");
	}

	@Test
	void approve_instagram_은_updateStoreInstagram_을_호출한다() {
		ChangeProposal proposal = createProposal("store.instagram_handle", "\"bbang_gram\"");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);

		proposalCommandService.approve(5L);

		then(servingWriter).should().updateStoreInstagram(1L, "old", "bbang_gram");
	}

	@Test
	void approve_store_category_는_updateStoreCategory_를_호출한다() {
		ChangeProposal proposal = createProposal("store.category", "\"BAKERY\"");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);

		proposalCommandService.approve(5L);

		then(servingWriter).should().updateStoreCategory(1L, "old", "BAKERY");
	}

	@Test
	void approve_business_hours_는_제안_당시_운영_세트를_조건으로_요일_세트를_교체한다() {
		ChangeProposal proposal = createProposal("store.business_hours",
			"[{\"day\":\"MONDAY\",\"open\":\"10:00\",\"close\":\"18:00\",\"closed\":false}]",
			"[{\"day\":\"MONDAY\",\"open\":\"09:00\",\"close\":\"18:00\",\"closed\":false},"
				+ "{\"day\":\"SUNDAY\",\"open\":null,\"close\":null,\"closed\":true}]");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);

		proposalCommandService.approve(5L);

		then(servingWriter).should().replaceBusinessHours(1L,
			java.util.List.of(new sweetgirl.dataops.snapshot.parser.BusinessHourRow("MONDAY", "10:00", "18:00", false)),
			java.util.List.of(
				new sweetgirl.dataops.snapshot.parser.BusinessHourRow("MONDAY", "09:00", "18:00", false),
				new sweetgirl.dataops.snapshot.parser.BusinessHourRow("SUNDAY", null, null, true)));
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.APPROVED);
	}

	@Test
	void approve_business_hours_채움은_운영에_영업시간이_없을_때만_쓴다() {
		// "매일" 수정분 채움 제안 — old_value 가 [] 면 기대 세트는 빈 세트다
		ChangeProposal proposal = createProposal("store.business_hours", "[]",
			"[{\"day\":\"MONDAY\",\"open\":\"11:00\",\"close\":\"21:50\",\"closed\":false}]");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);

		proposalCommandService.approve(5L);

		then(servingWriter).should().replaceBusinessHours(eq(1L), eq(java.util.List.of()), any());
	}

	@Test
	void approve_business_hours_운영_세트가_그새_바뀌었거나_DELETED_면_덮지_않고_FAILED_로_확정된다() {
		ChangeProposal proposal = createProposal("store.business_hours", "[]",
			"[{\"day\":\"MONDAY\",\"open\":\"11:00\",\"close\":\"21:50\",\"closed\":false}]");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);
		given(servingWriter.replaceBusinessHours(eq(1L), any(), any())).willReturn(0);

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class)
			.extracting("errorCode").isEqualTo(ProposalErrorCode.SERVING_VALUE_DRIFTED);
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.FAILED);
		then(applyStateService).should()
			.settleFailed(eq(5L), eq(ApplyOutcomeReason.VALUE_DRIFTED.name()), any());
	}

	@Test
	void approve_운영_쓰기_미설정이면_예외를_던지고_상태는_유지된다() {
		ChangeProposal proposal = createProposal("store.phone", "\"02-1\"");
		given(servingWriterProvider.getIfAvailable()).willReturn(null);

		assertThatThrownBy(() -> proposalCommandService.approve(5L)).isInstanceOf(ServingException.class);
		// 점유 전에 막히므로 PENDING 그대로다 — 운영 쓰기 경로가 없는데 제안을 소모해서는 안 된다
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PENDING);
		then(applyStateService).shouldHaveNoInteractions();
		then(applyLogCommandService).shouldHaveNoInteractions();
	}

	@Test
	void approve_지원하지_않는_target_은_예외이고_FAILED_로_확정된다() {
		ChangeProposal proposal = createProposal("store.unknown_field", "\"x\"");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);

		assertThatThrownBy(() -> proposalCommandService.approve(5L)).isInstanceOf(ProposalException.class);

		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.FAILED);
		then(applyStateService).should()
			.settleFailed(eq(5L), eq(ApplyOutcomeReason.TARGET_UNSUPPORTED.name()), any());
		then(servingWriter).shouldHaveNoInteractions();
	}

	@Test
	void approve_점유에_실패하면_운영에_아무것도_쓰지_않는다() {
		// 같은 제안에 동시 승인 두 요청 — 늦은 쪽은 PENDING 이 아니어서 점유에 실패한다.
		// 이전 구현은 상태 검사가 운영 DML 뒤에 있어 두 요청이 모두 운영에 썼다 (v3 F-03)
		given(changeProposalRepository.findById(5L))
			.willReturn(Optional.of(createProposal("store.phone", "\"02-1\"")));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		given(applyStateService.claim(5L))
			.willThrow(new ProposalException(ProposalErrorCode.PROPOSAL_NOT_CLAIMABLE, "현재 상태 APPLYING"));

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class)
			.extracting("errorCode").isEqualTo(ProposalErrorCode.PROPOSAL_NOT_CLAIMABLE);

		then(servingWriter).shouldHaveNoInteractions();
		then(applyLogCommandService).shouldHaveNoInteractions();
	}

	@Test
	void approve_운영_DB_오류는_되돌리지_않고_UNKNOWN_으로_세운다() {
		ChangeProposal proposal = createProposal("store.phone", "\"02-333-4444\"");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);
		given(servingWriter.updateStorePhone(1L, "old", "02-333-4444"))
			.willThrow(new DataAccessResourceFailureException("connection reset"));

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class)
			.extracting("errorCode").isEqualTo(ProposalErrorCode.PROPOSAL_APPLY_UNKNOWN);

		// 문장이 반영됐는지 알 수 없다 — PENDING 으로 되돌리면 같은 대상을 다시 쓴다
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.UNKNOWN);
		then(applyStateService).should()
			.settleUnknown(eq(5L), eq(ApplyOutcomeReason.SERVING_ERROR.name()), any());
		then(applyStateService).should(never()).settleApplied(any(), any(), any());
	}

	@Test
	void approve_운영_값이_그새_바뀌었으면_덮지_않고_FAILED_로_확정된다() {
		// 조건부 UPDATE 가 0행 — 제안 이후 어드민·사용자가 바꾼 값을 되돌리지 않는다 (v3 P1-1)
		ChangeProposal proposal = createProposal("store.phone", "\"02-333-4444\"");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);
		given(servingWriter.updateStorePhone(1L, "old", "02-333-4444")).willReturn(0);

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class)
			.extracting("errorCode").isEqualTo(ProposalErrorCode.SERVING_VALUE_DRIFTED);

		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.FAILED);
		// CAS 충돌은 운영 미반영 확정이고, 새 근거로 제안이 만료된 것과 사유를 구분한다
		then(applyStateService).should()
			.settleFailed(eq(5L), eq(ApplyOutcomeReason.VALUE_DRIFTED.name()), any());
		then(applyStateService).should(never()).settleApplied(any(), any(), any());
	}

	@Test
	void approve_제안_값을_해석할_수_없으면_운영_DML_전에_FAILED_로_확정된다() {
		ChangeProposal proposal = createProposal("store.phone", "{깨진 JSON");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
		stubApplyState(proposal);

		assertThatThrownBy(() -> proposalCommandService.approve(5L))
			.isInstanceOf(ProposalException.class)
			.extracting("errorCode").isEqualTo(ProposalErrorCode.PROPOSAL_VALUE_INVALID);

		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.FAILED);
		then(servingWriter).shouldHaveNoInteractions();
	}

	@Test
	void propose_다른_producer_의_제안은_지우지_않고_이번_제안을_접는다() {
		// 두 판정 주체가 같은 필드를 다르게 보는 상황은 자동으로 정할 일이 아니다 (v3 P1-2)
		ChangeProposal otherProducer = ChangeProposal.builder()
			.storeId(1L).target("store.status").oldValue("\"ACTIVE\"").newValue("{\"status\":\"DELETED\"}")
			.source(ProvenanceSource.LOCALDATA).producer(ProposalProducer.LOCALDATA_STATUS)
			.build();
		given(changeProposalRepository.findFirstByStoreIdAndTargetAndStatusIn(
			eq(1L), eq("store.status"), any())).willReturn(Optional.of(otherProducer));

		ChangeProposalResponse response = proposalCommandService.propose(
			1L, "store.status", "\"ACTIVE\"", "{\"status\":\"ACTIVE\"}", ProvenanceSource.NAVER, 9L,
			ProposalProducer.NAVER_SNAPSHOT_RECONCILE, "v1", UUID.randomUUID());

		assertThat(response).isNull();
		assertThat(otherProducer.getStatus()).isEqualTo(ProposalStatus.PENDING);
		assertThat(otherProducer.getReviewNote()).contains("NAVER_SNAPSHOT_RECONCILE").contains("사람이 정해야");
		then(changeProposalRepository).should(never()).save(any());
	}

	@Test
	void propose_다른_producer_라도_결론이_같으면_기존_제안을_그대로_쓴다() {
		ChangeProposal otherProducer = ChangeProposal.builder()
			.storeId(1L).target("store.status").newValue("{\"status\":\"DELETED\"}")
			.source(ProvenanceSource.LOCALDATA).producer(ProposalProducer.LOCALDATA_STATUS)
			.build();
		given(changeProposalRepository.findFirstByStoreIdAndTargetAndStatusIn(
			eq(1L), eq("store.status"), any())).willReturn(Optional.of(otherProducer));

		ChangeProposalResponse response = proposalCommandService.propose(
			1L, "store.status", null, "{\"status\":\"DELETED\"}", ProvenanceSource.NAVER, 9L,
			ProposalProducer.NAVER_SNAPSHOT_RECONCILE, "v1", UUID.randomUUID());

		assertThat(response).isNotNull();
		assertThat(otherProducer.getReviewNote()).isNull();
		then(changeProposalRepository).should(never()).save(any());
	}

	@Test
	void propose_producer_를_밝히지_않으면_LEGACY_UNKNOWN_으로_들어간다() {
		// 범위 정리에서 빠지는 대신, 알려진 producer 가 대체할 수 있다 — 만든 주체를 지어내지 않는다
		given(changeProposalRepository.save(any(ChangeProposal.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		proposalCommandService.propose(1L, "store.phone", null, "\"02-1\"", ProvenanceSource.NAVER, 7L);

		then(changeProposalRepository).should().save(argThat(
			proposal -> proposal.getProducer() == ProposalProducer.LEGACY_UNKNOWN));
	}

	@Test
	void propose_반영_중인_대상에는_새_제안을_만들지_않는다() {
		// APPLYING 중에 새 PENDING 을 만들면 같은 운영 필드에 두 번째 쓰기가 생긴다
		ChangeProposal applying = createProposal("store.phone", "\"02-1\"");
		applying.beginApply(UUID.randomUUID());
		given(changeProposalRepository.findFirstByStoreIdAndTargetAndStatusIn(
			eq(1L), eq("store.phone"), any())).willReturn(Optional.of(applying));

		ChangeProposalResponse response = proposalCommandService.propose(
			1L, "store.phone", "\"old\"", "\"02-9\"", ProvenanceSource.NAVER, 9L);

		assertThat(response).isNull();
		assertThat(applying.getStatus()).isEqualTo(ProposalStatus.APPLYING);
		then(changeProposalRepository).should(never()).save(any());
	}

	@Test
	void reject_는_운영에_아무것도_쓰지_않는다() {
		ChangeProposal proposal = createProposal("store.phone", "\"02-1\"");
		given(changeProposalRepository.findById(5L)).willReturn(Optional.of(proposal));

		proposalCommandService.reject(5L, "오매칭 의심");

		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.REJECTED);
		then(applyLogCommandService).shouldHaveNoInteractions();
		then(servingWriterProvider).shouldHaveNoInteractions();
	}
}
