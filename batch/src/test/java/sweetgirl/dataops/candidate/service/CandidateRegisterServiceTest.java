package sweetgirl.dataops.candidate.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import sweetgirl.dataops.candidate.dto.request.CandidateRegisterRequest;
import sweetgirl.dataops.candidate.dto.response.CandidateRegisterPreview;
import sweetgirl.dataops.candidate.dto.response.CandidateRegisterResult;
import sweetgirl.dataops.candidate.entity.NewStoreCandidate;
import sweetgirl.dataops.candidate.enums.CandidateRegistrationState;
import sweetgirl.dataops.candidate.enums.CandidateStatus;
import sweetgirl.dataops.candidate.repository.NewStoreCandidateRepository;
import sweetgirl.dataops.reconcile.repository.StoreFieldProvenanceRepository;
import sweetgirl.dataops.region.entity.RegionResolution;
import sweetgirl.dataops.region.repository.RegionResolutionRepository;
import sweetgirl.dataops.region.service.RegionEvidenceFinder;
import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.client.ServingWriter;
import sweetgirl.dataops.serving.service.ApplyLogCommandService;
import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CandidateRegisterServiceTest {

	@Mock
	private ObjectProvider<ServingReader> servingReaderProvider;
	@Mock
	private ObjectProvider<ServingWriter> servingWriterProvider;
	@Mock
	private ServingReader servingReader;
	@Mock
	private ServingWriter servingWriter;
	@Mock
	private NewStoreCandidateRepository candidateRepository;
	@Mock
	private StoreSyncStateRepository storeSyncStateRepository;
	@Mock
	private StoreFieldProvenanceRepository provenanceRepository;
	@Mock
	private ApplyLogCommandService applyLogCommandService;
	@Mock
	private RegionResolutionRepository regionResolutionRepository;

	private static final String REF = "mois-stanregin-api-20260926";
	private static final String PROJ = "app-3field-v2";

	private CandidateRegisterService service;

	@BeforeEach
	void setUp() {
		// 검토 결과 없는 재활성은 운영 조회 전에 막히므로 이 스텁을 쓰지 않는다 — lenient 로 둔다
		org.mockito.Mockito.lenient().when(servingReaderProvider.getIfAvailable()).thenReturn(servingReader);
		org.mockito.Mockito.lenient().when(servingWriterProvider.getIfAvailable()).thenReturn(servingWriter);
		service = new CandidateRegisterService(servingReaderProvider, servingWriterProvider,
			candidateRepository, storeSyncStateRepository, provenanceRepository,
			applyLogCommandService, new RegionEvidenceFinder(regionResolutionRepository, REF, PROJ),
			new ObjectMapper());
		// 기본: 등록 좌표(126.9, 37.5)를 지금 버전으로 역산한 근거가 있다 — 근거 없는 경우는 각 테스트가 덮어쓴다
		org.mockito.Mockito.lenient().when(regionResolutionRepository.findEvidence(eq(RegionEvidenceFinder.CANDIDATE),
				any(), eq(126.9), eq(37.5), eq(REF), eq(PROJ), any()))
			.thenAnswer(invocation -> List.of(evidenceRow(invocation.getArgument(1), "RESOLVED", null)));
	}

	private static RegionResolution evidenceRow(String mgmtNo, String status, String reason) {
		boolean resolved = "RESOLVED".equals(status);
		return RegionResolution.of(700L, RegionEvidenceFinder.CANDIDATE, mgmtNo, "REGISTRATION", 126.9, 37.5,
			status, reason, resolved ? "1144012300" : null, REF, PROJ,
			resolved ? "서울" : null, resolved ? "마포구" : null, resolved ? "망원동" : null);
	}

	private NewStoreCandidate matchedCandidate(String mgmtNo, String placeId) {
		return NewStoreCandidate.builder()
			.mgmtNo(mgmtNo).name("빵집").status(CandidateStatus.MATCHED).naverPlaceId(placeId)
			.build();
	}

	/** 등록 좌표 (126.9, 37.5) — 지역은 싣지 않는다. 서버가 이 좌표의 근거를 찾는다 */
	private CandidateRegisterRequest.Item item(String mgmtNo) {
		return new CandidateRegisterRequest.Item(
			mgmtNo, "우리빵집", "서울특별시 마포구 월드컵로 1", 126.9, 37.5, "02-123-4567");
	}

	@Test
	void 정상_등록은_insert_후_로그_프로비넌스_싱크편입_상태전이까지_수행한다() {
		NewStoreCandidate candidate = matchedCandidate("mg-1", "place-1");
		given(candidateRepository.findByMgmtNo("mg-1")).willReturn(Optional.of(candidate));
		given(servingReader.findStoreByNaverPlaceId("place-1")).willReturn(Optional.empty());
		given(servingWriter.insertStore(any(), any(), anyDouble(), anyDouble(), any(),
			any(), any(), any(), any())).willReturn(900L);

		CandidateRegisterResult result = service.registerBatch(
			new CandidateRegisterRequest(List.of(item("mg-1"))));

		assertThat(result.registered()).isEqualTo(1);
		// 지역은 요청이 아니라 등록 좌표의 법정동 근거에서 온다
		then(servingWriter).should().insertStore(eq("우리빵집"), any(), eq(126.9), eq(37.5), any(),
			eq("서울"), eq("마포구"), eq("망원동"), eq("place-1"));
		// 어떤 근거로 지역을 썼는지 apply_log 에 남는다
		then(applyLogCommandService).should().recordBeforeApply(eq(900L), eq("store.add"), isNull(),
			argThat(v -> v.contains("\"bjd_code\":\"1144012300\"") && v.contains("\"region_resolution_id\":700")
				&& v.contains("\"region_3rd\":\"망원동\"")),
			any(), isNull(), isNull());
		then(storeSyncStateRepository).should().save(any(StoreSyncState.class));
		then(candidateRepository).should().save(candidate);
		assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.REGISTERED);
		assertThat(candidate.getRegisteredStoreId()).isEqualTo(900L);
		assertThat(candidate.isLocationEvidenceVerified()).isTrue();
	}

	@Test
	void 같은_placeId가_운영에_있으면_INSERT_없이_기존_가게로_귀속한다() {
		NewStoreCandidate candidate = matchedCandidate("mg-2", "place-2");
		given(candidateRepository.findByMgmtNo("mg-2")).willReturn(Optional.of(candidate));
		given(servingReader.findStoreByNaverPlaceId("place-2"))
			.willReturn(Optional.of(new ServingReader.StoreRef(77L, "ACTIVE")));

		CandidateRegisterResult result = service.registerBatch(
			new CandidateRegisterRequest(List.of(item("mg-2"))));

		assertThat(result.dedupResolved()).isEqualTo(1);
		then(servingWriter).shouldHaveNoInteractions();
		assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.REGISTERED);
		assertThat(candidate.getRegisteredStoreId()).isEqualTo(77L);
	}

	@Test
	void DELETED_가게는_자동_재활성하지_않고_검토_경로로_보류한다() {
		// 한 번 제외된 가게를 사유를 보지 않고 되살리면 같은 이유로 다시 지워질 가게를 다시 넣는다 (v3 P1-3)
		NewStoreCandidate candidate = matchedCandidate("mg-7", "place-7");
		given(candidateRepository.findByMgmtNo("mg-7")).willReturn(Optional.of(candidate));
		given(servingReader.findStoreByNaverPlaceId("place-7"))
			.willReturn(Optional.of(new ServingReader.StoreRef(88L, "DELETED")));

		CandidateRegisterResult result = service.registerBatch(
			new CandidateRegisterRequest(List.of(item("mg-7"))));

		assertThat(result.held()).isEqualTo(1);
		then(servingWriter).should(never()).reactivateStore(anyLong());
		assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.MATCHED);
		assertThat(candidate.getRegistrationState())
			.isEqualTo(CandidateRegistrationState.HELD_DELETED_STORE);
	}

	@Test
	void 재활성은_검토_결과를_받은_별도_경로에서만_수행한다() {
		NewStoreCandidate candidate = matchedCandidate("mg-7", "place-7");
		given(candidateRepository.findByMgmtNo("mg-7")).willReturn(Optional.of(candidate));
		given(servingReader.findStoreByNaverPlaceId("place-7"))
			.willReturn(Optional.of(new ServingReader.StoreRef(88L, "DELETED")));
		given(servingWriter.reactivateStore(88L)).willReturn(1);

		assertThat(service.reactivateAfterReview("mg-7", "중복 정리로 지웠으나 별개 가게로 확인")).isTrue();

		then(applyLogCommandService).should()
			.recordBeforeApply(eq(88L), eq("store.status"), any(), any(), any(), isNull(), isNull());
		assertThat(candidate.getRegisteredStoreId()).isEqualTo(88L);
		// 기존 가게이므로 생성 provenance 를 지어내지 않는다 — store.status 만 남긴다
		then(provenanceRepository).should(never()).save(argThat(
			provenance -> "store.add".equals(provenance.getField())));
	}

	@Test
	void 재활성은_검토_결과가_없으면_거부한다() {
		assertThatThrownBy(() -> service.reactivateAfterReview("mg-7", "  "))
			.isInstanceOf(IllegalArgumentException.class);
		then(servingWriter).should(never()).reactivateStore(anyLong());
	}

	@Test
	void 등록_좌표의_법정동_근거가_없으면_신규_INSERT_를_보류한다() {
		NewStoreCandidate candidate = matchedCandidate("mg-8", "place-8");
		given(candidateRepository.findByMgmtNo("mg-8")).willReturn(Optional.of(candidate));
		given(servingReader.findStoreByNaverPlaceId("place-8")).willReturn(Optional.empty());
		given(regionResolutionRepository.findEvidence(eq(RegionEvidenceFinder.CANDIDATE), eq("mg-8"),
			anyDouble(), anyDouble(), any(), any(), any())).willReturn(List.of());

		CandidateRegisterResult result = service.registerBatch(
			new CandidateRegisterRequest(List.of(item("mg-8"))));

		assertThat(result.held()).isEqualTo(1);
		then(servingWriter).should(never()).insertStore(any(), any(), anyDouble(), anyDouble(), any(),
			any(), any(), any(), any());
		// 근거가 갖춰지면 다시 시도할 수 있게 MATCHED 로 남는다
		assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.MATCHED);
		assertThat(candidate.getRegistrationState())
			.isEqualTo(CandidateRegistrationState.HELD_NO_LOCATION_EVIDENCE);
		assertThat(candidate.getRegistrationNote()).contains("법정동 해소 근거가 없습니다");
		assertThat(candidate.isLocationEvidenceVerified()).isFalse();
	}

	@Test
	void 등록_좌표의_최신_근거가_해소되지_않았으면_보류한다() {
		// H 만 온 응답 — 행정동을 법정동 대신 쓰지 않는다
		NewStoreCandidate candidate = matchedCandidate("mg-12", "place-12");
		given(candidateRepository.findByMgmtNo("mg-12")).willReturn(Optional.of(candidate));
		given(servingReader.findStoreByNaverPlaceId("place-12")).willReturn(Optional.empty());
		given(regionResolutionRepository.findEvidence(eq(RegionEvidenceFinder.CANDIDATE), eq("mg-12"),
			anyDouble(), anyDouble(), any(), any(), any()))
			.willReturn(List.of(evidenceRow("mg-12", "UNRESOLVED", "NO_B_DOCUMENT")));

		CandidateRegisterResult result = service.registerBatch(
			new CandidateRegisterRequest(List.of(item("mg-12"))));

		assertThat(result.held()).isEqualTo(1);
		then(servingWriter).should(never()).insertStore(any(), any(), anyDouble(), anyDouble(), any(),
			any(), any(), any(), any());
		assertThat(candidate.getRegistrationNote()).contains("NO_B_DOCUMENT");
	}

	@Test
	void 만든_적이_있는_후보를_운영에서_placeId_로_못_찾으면_다시_INSERT_하지_않는다() {
		// 근거가 있어도 — 이미 store 를 만든 후보를 다시 INSERT 하면 중복 등재다
		NewStoreCandidate candidate = matchedCandidate("mg-13", "place-13");
		candidate.markStorePresent(55L, false);
		candidate.markNeedsRecovery("sync_state 없음");
		given(candidateRepository.findByMgmtNo("mg-13")).willReturn(Optional.of(candidate));
		given(servingReader.findStoreByNaverPlaceId("place-13")).willReturn(Optional.empty());

		CandidateRegisterResult result = service.registerBatch(
			new CandidateRegisterRequest(List.of(item("mg-13"))));

		assertThat(result.held()).isEqualTo(1);
		then(servingWriter).shouldHaveNoInteractions();
		assertThat(candidate.getRegistrationState()).isEqualTo(CandidateRegistrationState.NEEDS_RECOVERY);
		assertThat(candidate.getRegisteredStoreId()).isEqualTo(55L);
		assertThat(candidate.getRegistrationNote()).contains("store 55");
	}

	@Test
	void 미리보기는_운영과_로컬에_쓰지_않고_등록과_같은_판정을_돌려준다() {
		NewStoreCandidate fresh = matchedCandidate("mg-20", "place-20");
		NewStoreCandidate dup = matchedCandidate("mg-21", "place-21");
		NewStoreCandidate noEvidence = matchedCandidate("mg-22", "place-22");
		given(candidateRepository.findByMgmtNo("mg-20")).willReturn(Optional.of(fresh));
		given(candidateRepository.findByMgmtNo("mg-21")).willReturn(Optional.of(dup));
		given(candidateRepository.findByMgmtNo("mg-22")).willReturn(Optional.of(noEvidence));
		given(servingReader.findStoreByNaverPlaceId("place-20")).willReturn(Optional.empty());
		given(servingReader.findStoreByNaverPlaceId("place-21"))
			.willReturn(Optional.of(new ServingReader.StoreRef(77L, "ACTIVE")));
		given(servingReader.findStoreByNaverPlaceId("place-22")).willReturn(Optional.empty());
		given(regionResolutionRepository.findEvidence(eq(RegionEvidenceFinder.CANDIDATE), eq("mg-22"),
			anyDouble(), anyDouble(), any(), any(), any())).willReturn(List.of());

		List<CandidateRegisterPreview> previews = service.preview(
			new CandidateRegisterRequest(List.of(item("mg-20"), item("mg-21"), item("mg-22"))), false);

		assertThat(previews).extracting(CandidateRegisterPreview::plan)
			.containsExactly("INSERT", "DEDUP", "HOLD_NO_LOCATION_EVIDENCE");
		assertThat(previews.get(0).evidence().region3rd()).isEqualTo("망원동");
		assertThat(previews.get(0).evidence().resolutionId()).isEqualTo(700L);
		assertThat(previews.get(1).existingStoreId()).isEqualTo(77L);
		// 판정만 — 어느 쪽에도 쓰지 않는다
		then(servingWriter).shouldHaveNoInteractions();
		then(candidateRepository).should(never()).save(any());
		then(candidateRepository).should(never()).saveAndFlush(any());
		then(applyLogCommandService).shouldHaveNoInteractions();
		then(storeSyncStateRepository).shouldHaveNoInteractions();
		then(provenanceRepository).shouldHaveNoInteractions();
		assertThat(fresh.getRegistrationState()).isNull();
		assertThat(noEvidence.getRegistrationState()).isNull();
	}

	@Test
	void probe_는_INSERT_판정에만_운영_INSERT_를_실행하고_롤백한다() {
		NewStoreCandidate fresh = matchedCandidate("mg-30", "place-30");
		NewStoreCandidate dup = matchedCandidate("mg-31", "place-31");
		NewStoreCandidate rejected = matchedCandidate("mg-32", "place-32");
		given(candidateRepository.findByMgmtNo("mg-30")).willReturn(Optional.of(fresh));
		given(candidateRepository.findByMgmtNo("mg-31")).willReturn(Optional.of(dup));
		given(candidateRepository.findByMgmtNo("mg-32")).willReturn(Optional.of(rejected));
		given(servingReader.findStoreByNaverPlaceId("place-30")).willReturn(Optional.empty());
		given(servingReader.findStoreByNaverPlaceId("place-31"))
			.willReturn(Optional.of(new ServingReader.StoreRef(77L, "ACTIVE")));
		given(servingReader.findStoreByNaverPlaceId("place-32")).willReturn(Optional.empty());
		given(servingWriter.probeInsertStore(any(), any(), anyDouble(), anyDouble(), any(),
			any(), any(), any(), eq("place-30"))).willReturn(123456L);
		given(servingWriter.probeInsertStore(any(), any(), anyDouble(), anyDouble(), any(),
			any(), any(), any(), eq("place-32"))).willThrow(new RuntimeException("check constraint 위반"));

		List<CandidateRegisterPreview> previews = service.preview(
			new CandidateRegisterRequest(List.of(item("mg-30"), item("mg-31"), item("mg-32"))), true);

		assertThat(previews.get(0).probeRowId()).isEqualTo(123456L);
		assertThat(previews.get(1).probeRowId()).isNull();
		assertThat(previews.get(2).probeError()).contains("check constraint");
		then(servingWriter).should().probeInsertStore(eq("우리빵집"), any(), eq(126.9), eq(37.5), any(),
			eq("서울"), eq("마포구"), eq("망원동"), eq("place-30"));
		then(servingWriter).should(never()).insertStore(any(), any(), anyDouble(), anyDouble(), any(),
			any(), any(), any(), any());
		then(candidateRepository).should(never()).save(any());
		then(applyLogCommandService).shouldHaveNoInteractions();
	}

	@Test
	void 기존_가게_귀속도_sync_state_를_확인해_빠졌으면_채운다() {
		// 2026-08-25 실측 4건 — 기존 placeId 로 귀속한 가게가 sync_state 없이 REGISTERED 로 닫혀 한 번도 수집되지 않았다
		NewStoreCandidate candidate = matchedCandidate("mg-9", "place-9");
		given(candidateRepository.findByMgmtNo("mg-9")).willReturn(Optional.of(candidate));
		given(servingReader.findStoreByNaverPlaceId("place-9"))
			.willReturn(Optional.of(new ServingReader.StoreRef(77L, "ACTIVE")));
		given(storeSyncStateRepository.findByStoreIdAndSource(77L, SyncSource.NAVER))
			.willReturn(Optional.empty());

		service.registerBatch(new CandidateRegisterRequest(List.of(item("mg-9"))));

		then(storeSyncStateRepository).should().save(any(StoreSyncState.class));
		assertThat(candidate.getRegistrationState()).isEqualTo(CandidateRegistrationState.COMPLETED);
		// 기존 가게에 생성 provenance 를 지어내지 않는다
		then(provenanceRepository).shouldHaveNoInteractions();
	}

	@Test
	void 후속_보완에_실패하면_운영_store_는_인정하고_복구_필요로_남긴다() {
		NewStoreCandidate candidate = matchedCandidate("mg-10", "place-10");
		given(candidateRepository.findByMgmtNo("mg-10")).willReturn(Optional.of(candidate));
		given(servingReader.findStoreByNaverPlaceId("place-10")).willReturn(Optional.empty());
		given(servingWriter.insertStore(any(), any(), anyDouble(), anyDouble(), any(),
			any(), any(), any(), any())).willReturn(900L);
		given(storeSyncStateRepository.findByStoreIdAndSource(900L, SyncSource.NAVER))
			.willReturn(Optional.empty());
		given(storeSyncStateRepository.save(any(StoreSyncState.class)))
			.willThrow(new RuntimeException("로컬 DB 오류"));

		service.registerBatch(new CandidateRegisterRequest(List.of(item("mg-10"))));

		// 운영에 가게는 있다 — 그 사실을 지우지 않는다
		assertThat(candidate.getRegisteredStoreId()).isEqualTo(900L);
		assertThat(candidate.getRegistrationState()).isEqualTo(CandidateRegistrationState.NEEDS_RECOVERY);
		assertThat(candidate.getRegistrationNote()).contains("후속 보완 실패");
	}

	@Test
	void INSERT_결과를_모르면_되돌리지_않고_UNKNOWN_으로_세워_재시도를_막는다() {
		NewStoreCandidate candidate = matchedCandidate("mg-11", "place-11");
		given(candidateRepository.findByMgmtNo("mg-11")).willReturn(Optional.of(candidate));
		given(servingReader.findStoreByNaverPlaceId("place-11")).willReturn(Optional.empty());
		given(servingWriter.insertStore(any(), any(), anyDouble(), anyDouble(), any(),
			any(), any(), any(), any())).willThrow(new RuntimeException("응답 유실"));

		CandidateRegisterResult first = service.registerBatch(
			new CandidateRegisterRequest(List.of(item("mg-11"))));
		assertThat(first.unknown()).isEqualTo(1);
		assertThat(candidate.getRegistrationState()).isEqualTo(CandidateRegistrationState.UNKNOWN);
		assertThat(candidate.getRegistrationOperationId()).isNotNull();

		// 재실행해도 다시 쓰지 않는다 — 확인은 P3 의 자동 대사가 맡는다
		CandidateRegisterResult second = service.registerBatch(
			new CandidateRegisterRequest(List.of(item("mg-11"))));
		assertThat(second.unknown()).isEqualTo(1);
		then(servingWriter).should(times(1)).insertStore(any(), any(), anyDouble(), anyDouble(), any(),
			any(), any(), any(), any());
	}

	@Test
	void 큐에_없거나_MATCHED가_아니면_스킵한다_재실행_멱등() {
		given(candidateRepository.findByMgmtNo("mg-3")).willReturn(Optional.empty());
		NewStoreCandidate done = matchedCandidate("mg-4", "place-4");
		done.markStorePresent(50L, true);
		done.completeRegistration();
		given(candidateRepository.findByMgmtNo("mg-4")).willReturn(Optional.of(done));

		CandidateRegisterResult result = service.registerBatch(
			new CandidateRegisterRequest(List.of(item("mg-3"), item("mg-4"))));

		assertThat(result.skipped()).isEqualTo(2);
		then(servingWriter).shouldHaveNoInteractions();
	}

	@Test
	void 한_항목의_실패는_배치의_나머지를_막지_않는다() {
		NewStoreCandidate boom = matchedCandidate("mg-5", "place-5");
		NewStoreCandidate fine = matchedCandidate("mg-6", "place-6");
		given(candidateRepository.findByMgmtNo("mg-5")).willReturn(Optional.of(boom));
		given(candidateRepository.findByMgmtNo("mg-6")).willReturn(Optional.of(fine));
		given(servingReader.findStoreByNaverPlaceId("place-5"))
			.willThrow(new RuntimeException("prod down"));
		given(servingReader.findStoreByNaverPlaceId("place-6")).willReturn(Optional.empty());
		given(servingWriter.insertStore(any(), any(), anyDouble(), anyDouble(), any(),
			any(), any(), any(), any())).willReturn(901L);

		CandidateRegisterResult result = service.registerBatch(
			new CandidateRegisterRequest(List.of(item("mg-5"), item("mg-6"))));

		assertThat(result.failed()).isEqualTo(1);
		assertThat(result.failedMgmtNos()).containsExactly("mg-5");
		assertThat(result.registered()).isEqualTo(1);
	}
}
