package sweetgirl.dataops.proposal.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import sweetgirl.dataops.agentapply.AgentApplyRequest;
import sweetgirl.dataops.agentapply.AgentApplyHold;
import sweetgirl.dataops.agentapply.AgentManifestApplyGate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.dataops.proposal.code.ProposalErrorCode;
import sweetgirl.dataops.proposal.dto.response.ChangeProposalResponse;
import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.enums.ApplyOutcomeReason;
import sweetgirl.dataops.proposal.enums.ProposalProducer;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.exception.ProposalException;
import sweetgirl.dataops.proposal.policy.LocationProposalGate;
import sweetgirl.dataops.proposal.policy.MenuRemovalProposalGate;
import sweetgirl.dataops.region.dto.LocationEvidence;
import sweetgirl.dataops.proposal.repository.ChangeProposalRepository;
import sweetgirl.dataops.reconcile.policy.MenuCategoryClassifier;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.client.ServingWriter;
import sweetgirl.dataops.serving.code.ServingErrorCode;
import sweetgirl.dataops.serving.dto.MenuUpsertResult;
import sweetgirl.dataops.serving.enums.ApplyOrigin;
import sweetgirl.dataops.serving.exception.ServingException;
import sweetgirl.dataops.serving.service.ApplyLogCommandService;
import sweetgirl.dataops.snapshot.parser.BusinessHourRow;
import sweetgirl.dataops.snapshot.concurrency.StoreObservationBarrier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

/**
 * 승인 워크플로 — 승인 시 순서는 설계서 4.2 + v3 P1-1 의 점유를 앞에 붙인 네 단계로 고정한다:
 * ① 점유(PENDING → APPLYING, REQUIRES_NEW 즉시 확정) → ② apply_log 선기록(REQUIRES_NEW)
 * → ③ 운영 DML(ServingWriter) → ④ 결과 확정(APPROVED/FAILED/UNKNOWN + provenance, REQUIRES_NEW).
 *
 * ①이 운영 쓰기보다 먼저 commit 되므로 같은 제안에 동시 승인 두 요청이 와도 하나만 운영에 쓴다.
 * ④의 갈림은 ③이 던진 예외의 종류로 판정한다 — 자세한 규율은 {@link #applyToServing} 주석 참조.
 * 승인된 값의 provenance source 는 데이터 원천(NAVER 등)이다 — ADMIN 이 아니다 (설계서 3.3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProposalCommandService {

	private static final Pattern MENU_PRICE_TARGET = Pattern.compile("^menu\\.(\\d+)\\.price$");
	private static final Pattern MENU_REMOVED_TARGET = Pattern.compile("^menu\\.(\\d+)\\.removed$");
	private static final Pattern MENU_DESCRIPTION_TARGET = Pattern.compile("^menu\\.(\\d+)\\.description$");
	private static final Pattern MENU_CATEGORY_TARGET = Pattern.compile("^menu\\.(\\d+)\\.category$");
	private static final String MENU_ADD_TARGET_PREFIX = "menu.add.";
	/** 부분 유니크 uq_proposal_in_flight 와 같은 범위 — 이 상태의 제안이 있으면 같은 대상에 새 제안을 만들지 않는다 */
	private static final List<ProposalStatus> IN_FLIGHT_STATUSES =
		List.of(ProposalStatus.PENDING, ProposalStatus.APPLYING, ProposalStatus.UNKNOWN);

	private final ChangeProposalRepository changeProposalRepository;
	private final ProposalApplyStateService applyStateService;
	private final ApplyLogCommandService applyLogCommandService;
	private final ObjectProvider<ServingWriter> servingWriterProvider;
	private final ObjectProvider<ServingReader> servingReaderProvider;
	private final LocationProposalGate locationProposalGate;
	private final MenuRemovalProposalGate menuRemovalProposalGate;
	private final StoreObservationBarrier observationBarrier;
	private final ObjectMapper objectMapper;
	private final MenuCategoryClassifier menuCategoryClassifier;
	private AgentManifestApplyGate manifestApplyGate;

	@Autowired(required = false)
	public void configureManifestApplyGate(AgentManifestApplyGate gate) { this.manifestApplyGate = gate; }

	/** Dedicated non-web runner entry. The writer is private to that runner, not a global Spring writer bean. */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public ChangeProposalResponse approveExactManifest(Long proposalId, AgentApplyRequest request, ServingWriter writer) {
		if (manifestApplyGate == null || writer == null) throw new AgentApplyHold("MANIFEST_APPLY_RUNNER_REQUIRED");
		ChangeProposal candidate = getProposal(proposalId);
		if (manifestApplyGate.alreadyApplied(candidate, request)) return ChangeProposalResponse.from(candidate);
		// Every agent field, not just menu deletion, serializes against raw capture reception.
		try (var barrier = observationBarrier.lockApproval(candidate.getStoreId())) {
			manifestApplyGate.verifyBeforeWrite(candidate,request);
			return approveGuarded(proposalId,candidate,writer,barrier,request);
		}
	}

	/** 운영 DML 결과 — provenance 에 기록할 field 키와, 반영 방식이 제안과 달랐을 때의 메모 */
	private record ApplyOutcome(String provenanceField, String note) {
		static ApplyOutcome of(String provenanceField) {
			return new ApplyOutcome(provenanceField, null);
		}
	}

	/** producer 를 밝히지 않는 호출 — LEGACY_UNKNOWN 으로 들어가며 어떤 재판정 범위에도 속하지 않는다 */
	public ChangeProposalResponse propose(Long storeId, String target, String oldValue, String newValue,
			ProvenanceSource source, Long snapshotId) {
		return propose(storeId, target, oldValue, newValue, source, snapshotId,
			ProposalProducer.LEGACY_UNKNOWN, null, null);
	}

	/**
	 * diff 수납 — 같은 대상의 PENDING 이 있으면 동일 제안은 스킵, 다른 값이면 supersede 후 신규.
	 * 같은 값으로 REJECTED 된 이력이 있으면 재상정하지 않는다(null 반환) — 재판정이 다시 판정할 때
	 * 이미 사람이 거절한 diff 가 되살아나는 것을 막는다.
	 * 반영 중(APPLYING)인 대상도 건너뛴다 — 같은 운영 필드에 두 번째 쓰기를 만들지 않는다.
	 *
	 * 같은 대상에 <b>다른 producer</b> 의 진행 중 제안이 있으면 그것을 지우지 않고 이번 제안을 접는다 (v3 P1-2) —
	 * 두 판정 주체가 같은 필드를 다르게 보는 상황은 자동으로 정할 일이 아니라 사람이 볼 일이다.
	 * 그 사실은 기존 제안의 review_note 에 남긴다.
	 *
	 * @return 진행 중으로 남은(또는 새로 만든) 제안. 스킵·보존으로 아무것도 남기지 않았으면 null
	 */
	public ChangeProposalResponse propose(Long storeId, String target, String oldValue, String newValue,
			ProvenanceSource source, Long snapshotId, ProposalProducer producer, String policyVersion,
			UUID reconcileRunId) {
		if (changeProposalRepository.existsByStoreIdAndTargetAndNewValueAndStatus(
			storeId, target, newValue, ProposalStatus.REJECTED)) {
			return null;
		}
		ChangeProposal existing = changeProposalRepository
			.findFirstByStoreIdAndTargetAndStatusIn(storeId, target, IN_FLIGHT_STATUSES)
			.orElse(null);
		if (existing != null && (existing.getStatus() == ProposalStatus.APPLYING || existing.getStatus() == ProposalStatus.UNKNOWN)) {
			// 반영이 끝나면 다음 스윕이 지금 운영 값과 다시 비교한다 — supersede 도 신규도 하지 않는다
			return null;
		}
		if (existing != null && target.startsWith("menu.") && existing.getSource() != source) {
			changeProposalRepository.updatePendingReviewNote(existing.getId(), existing.getSource(),
				"MENU_SOURCE_CONFLICT: 기존 후보 " + existing.getSource() + " / 새 관측 " + source
				+ " — 출처가 다른 후보를 자동 교체하지 않습니다");
			return ChangeProposalResponse.from(existing);
		}
		if (existing != null && conflictsAcrossProducers(existing, producer, newValue)) {
			changeProposalRepository.updatePendingReviewNote(existing.getId(), existing.getSource(), String.format(
				"%s 가 같은 대상에 다른 값을 제안했으나 보존을 위해 접었습니다 — 어느 판정이 맞는지 사람이 정해야 합니다",
				producer));
			log.warn("producer 충돌 — store {} target {} : 기존 {} 유지, {} 제안 접음",
				storeId, target, existing.getProducer(), producer);
			return null;
		}
		if (existing != null) {
			if (Objects.equals(existing.getNewValue(), newValue)) {
				return ChangeProposalResponse.from(existing);
			}
			existing.supersede();
			// 하이버네이트는 flush 때 INSERT 를 UPDATE 보다 먼저 보낸다 — 먼저 내보내지 않으면 새 PENDING 과 옛 PENDING 이
			// 잠깐 같이 있어 uq_proposal_in_flight(store_id, target) 에 걸린다 (09-20 두 번째 스윕 3995 store.address 500)
			changeProposalRepository.flush();
		}
		ChangeProposal proposal = changeProposalRepository.save(ChangeProposal.builder()
			.storeId(storeId)
			.target(target)
			.oldValue(oldValue)
			.newValue(newValue)
			.source(source)
			.snapshotId(snapshotId)
			.producer(producer)
			.policyVersion(policyVersion)
			.reconcileRunId(reconcileRunId)
			.build());
		return ChangeProposalResponse.from(proposal);
	}

	/** Both automatic policy and explicit review see the source uncertainty before writing. */
	public void noteMenuSourceComparison(Long proposalId, ProvenanceSource previous, ProvenanceSource observed) {
		ChangeProposal proposal = getProposal(proposalId);
		if (proposal.getStatus() != ProposalStatus.PENDING || proposal.getSource() != observed) return;
		String previousNote = Objects.toString(proposal.getReviewNote(), "");
		if (previousNote.contains("SOURCE_CONFLICT") || previousNote.contains("PRODUCER_CONFLICT")
			|| previousNote.contains("다른 값을 제안")) return;
		String reason = previous == observed ? "MENU_SAME_SOURCE_UPDATE"
			: previous == null ? "MENU_SOURCE_UNKNOWN" : "MENU_SOURCE_CONFLICT";
		changeProposalRepository.updatePendingReviewNote(proposalId, observed,
			reason + ": 기존 " + (previous == null ? "미기록" : previous)
			+ " / 관측 " + observed + (observed == ProvenanceSource.BAEMIN ? " (배달 가격으로 매장 가격과 다를 수 있음)" : "")
			+ " — 출처·현재 값 정책 검증 후 반영");
	}

	/**
	 * 다른 판정 주체의 제안인가 — LEGACY_UNKNOWN 은 보호를 주장하지 않는다(만든 주체를 모르므로 지금 판정이 대체한다).
	 * 값이 같으면 충돌이 아니다 — 두 주체가 같은 결론에 온 것이므로 기존 제안을 그대로 쓴다.
	 */
	private boolean conflictsAcrossProducers(ChangeProposal existing, ProposalProducer producer, String newValue) {
		if (Objects.equals(existing.getNewValue(), newValue)) {
			return false;   // 두 주체가 같은 결론 — 기존 제안을 그대로 쓴다
		}
		return existing.getProducer() != ProposalProducer.LEGACY_UNKNOWN
			&& existing.getProducer() != producer;
	}

	/**
	 * 승인 — 네 단계가 각각 독립 commit 이므로 이 메서드는 트랜잭션을 열지 않는다(NOT_SUPPORTED).
	 * 한 트랜잭션으로 묶으면 점유가 운영 쓰기보다 먼저 확정되지 않고, 실패 시 결과 상태까지 함께 사라진다.
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public ChangeProposalResponse approve(Long proposalId) {
		if (manifestApplyGate != null) throw new AgentApplyHold("PLAIN_APPROVAL_DISABLED_IN_MANIFEST_RUNNER");
		ServingWriter servingWriter = servingWriterProvider.getIfAvailable();
		if (servingWriter == null) {
			throw new ServingException(ServingErrorCode.PROD_DB_NOT_CONFIGURED);
		}

		ChangeProposal candidate = getProposal(proposalId);
		if (menuRemovalProposalGate.handles(candidate.getTarget())) {
			try (var barrier = observationBarrier.lockApproval(candidate.getStoreId())) {
				return approveGuarded(proposalId, candidate, servingWriter, barrier, null);
			}
		}
		return approveGuarded(proposalId, candidate, servingWriter, null, null);
	}

	private ChangeProposalResponse approveGuarded(Long proposalId, ChangeProposal candidate,
			ServingWriter servingWriter, StoreObservationBarrier.ApprovalScope barrier, AgentApplyRequest manifestRequest) {
		return approveGuarded(proposalId, candidate, servingWriter, barrier, manifestRequest, null);
	}

	public record AutomaticApproval(String externalId, java.util.function.Consumer<ChangeProposal> verify) {}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public ChangeProposalResponse approveAutomatically(Long proposalId, ServingWriter writer,
			StoreObservationBarrier.ApprovalScope barrier, AutomaticApproval approval) {
		if (manifestApplyGate != null || writer == null || barrier == null || approval == null
			|| approval.externalId() == null || approval.externalId().isBlank())
			throw new sweetgirl.dataops.autoapply.AutomaticApplyHold("AUTOMATIC_SCOPE_REQUIRED");
		barrier.assertHeld();
		return approveGuarded(proposalId, getProposal(proposalId), writer, barrier, null, approval);
	}

	private ChangeProposalResponse approveGuarded(Long proposalId, ChangeProposal candidate,
			ServingWriter servingWriter, StoreObservationBarrier.ApprovalScope barrier, AgentApplyRequest manifestRequest,
			AutomaticApproval automatic) {
		// ⓪ 위치·메뉴 부재 제안 보류 게이트 — 점유 전에 본다. 보류는 실패가 아니라 "아직 승인하지 않는다"이므로
		//    제안은 PENDING 으로 남아야 한다 (근거가 붙으면 그때 승인).
		//    근거 부족은 점유·apply_log·운영 쓰기 전에 멈춘다.
		//    통과하면 지역 근거(region_resolution)를 돌려준다 — 승인은 제안 JSON 이 아니라 이 근거의 지역을 쓴다 (P4-4)
		LocationEvidence evidence = null;
		if (locationProposalGate.handles(candidate.getTarget())) {
			evidence = locationProposalGate.verify(candidate.getTarget(), candidate.getStoreId(),
				parseValue(candidate.getNewValue(), "new_value"), servingReaderProvider.getIfAvailable());
		}

		if (menuRemovalProposalGate.handles(candidate.getTarget())) {
			menuRemovalProposalGate.verify(candidate, servingReaderProvider.getIfAvailable());
		}

		if (barrier != null) barrier.assertHeld();

		// ① 점유 — 운영 쓰기 전에 확정한다. 비PENDING(중복 요청·거절 제안 재승인·이미 반영된 건)은 여기서 막힌다
		UUID operationId = automatic != null ? applyStateService.claimAutomatic(proposalId, automatic.verify())
			: manifestRequest == null ? applyStateService.claim(proposalId) : applyStateService.claimExactManifest(proposalId, manifestRequest);
		ChangeProposal proposal = getProposal(proposalId);

		// ② apply_log 선기록 — 운영 값 대사의 기준선. 위치 제안은 실제로 쓸 지역·근거까지 실어 둔다
		//    (제안 JSON 에는 지역이 없을 수 있다 — 로그가 운영에 쓴 값과 달라지면 대사가 거짓이 된다)
		applyLogCommandService.recordBeforeApply(
			proposal.getStoreId(), proposal.getTarget(), proposal.getOldValue(),
			withEvidence(proposal.getNewValue(), evidence),
			ApplyOrigin.PROPOSAL, proposal.getId(), proposal.getSnapshotId());

		// ③ 운영 DML
		ApplyOutcome outcome;
		try {
			if (barrier != null) barrier.assertHeld();
			String verifiedExternalId = automatic != null ? automatic.externalId()
				: manifestRequest == null ? null : manifestApplyGate.verifyBeforeWrite(proposal, manifestRequest);
			if (manifestRequest != null && (verifiedExternalId == null || verifiedExternalId.isBlank()))
				throw new AgentApplyHold("VERIFIED_EXTERNAL_ID_REQUIRED");
			outcome = applyToServing(servingWriter, proposal, evidence, verifiedExternalId);
		} catch (AgentApplyHold held) {
			applyStateService.settleFailed(proposalId,"AGENT_APPROVAL_HELD",held.getMessage());
			throw held;
		} catch (ProposalException e) {
			applyStateService.settleFailed(proposalId, failureReason(e).name(), e.getMessage());
			throw e;
		} catch (DataAccessException e) {
			// 문장을 보낸 뒤의 실패는 반영 여부를 알 수 없다 — 되돌리지 않고 미확정으로 세운다
			applyStateService.settleUnknown(proposalId, ApplyOutcomeReason.SERVING_ERROR.name(), e.toString());
			throw new ProposalException(ProposalErrorCode.PROPOSAL_APPLY_UNKNOWN,
				"operation " + operationId + " — " + e.getMessage());
		} catch (RuntimeException e) {
			applyStateService.settleUnknown(proposalId, ApplyOutcomeReason.INTERRUPTED.name(), e.toString());
			throw new ProposalException(ProposalErrorCode.PROPOSAL_APPLY_UNKNOWN,
				"operation " + operationId + " — " + e);
		}

		// ④ 결과 확정 — 상태와 provenance 를 한 트랜잭션에. 위치는 어떤 근거로 지역을 썼는지 남긴다
		String note = evidence == null ? outcome.note() : String.format("지역 근거 #%d (법정동 %s, %s)",
			evidence.resolutionId(), evidence.bjdCode(), evidence.referenceVersion());
		applyStateService.settleApplied(proposalId, outcome.provenanceField(), note);
		log.info("proposal 승인·반영 — id={}, store={}, target={}, operation={}",
			proposal.getId(), proposal.getStoreId(), proposal.getTarget(), operationId);
		return ChangeProposalResponse.from(getProposal(proposalId));
	}

	/**
	 * 운영 미반영 확정 사유 — {@link ProposalException} 은 모두 운영 DML 전이나 0행에서 던져진다.
	 * 여기 없는 코드는 남은 경우를 드러내려고 TARGET_UNSUPPORTED 로 뭉치지 않고 INVALID_PROPOSAL_VALUE 로 둔다.
	 */
	private ApplyOutcomeReason failureReason(ProposalException e) {
		if (e.getErrorCode() == ProposalErrorCode.SERVING_VALUE_DRIFTED) {
			return ApplyOutcomeReason.VALUE_DRIFTED;
		}
		if (e.getErrorCode() == ProposalErrorCode.MENU_CATEGORY_CHANGED) {
			return ApplyOutcomeReason.MENU_CATEGORY_CHANGED;
		}
		if (e.getErrorCode() == ProposalErrorCode.MENU_CONFLICTS_USER_MENU
			|| e.getErrorCode() == ProposalErrorCode.MENU_CONFLICTS_DISTINCT_NAME) {
			return ApplyOutcomeReason.MENU_CONFLICT;
		}
		if (e.getErrorCode() == ProposalErrorCode.PROPOSAL_TARGET_UNSUPPORTED) {
			return ApplyOutcomeReason.TARGET_UNSUPPORTED;
		}
		return ApplyOutcomeReason.INVALID_PROPOSAL_VALUE;
	}

	public ChangeProposalResponse reject(Long proposalId, String note) {
		ChangeProposal proposal = getProposal(proposalId);
		proposal.reject(note);
		return ChangeProposalResponse.from(proposal);
	}

	/**
	 * target 을 해석해 운영 DML 실행.
	 *
	 * 결과 상태 규율 (v3 P1-1) — 호출자는 여기서 나온 예외의 종류로 FAILED/UNKNOWN 을 가른다:
	 * <ul>
	 *   <li>{@link ProposalException} 은 반드시 운영 DML 전이나 0행(아무것도 쓰지 않음)에서만 던진다 → FAILED.
	 *       새 분기를 넣을 때 이 불변식을 깨면 운영에 쓴 건이 "미반영 확정"으로 기록된다</li>
	 *   <li>제안 값 파싱은 첫 DML 보다 먼저 한다 — 파싱 실패도 FAILED 여야 한다</li>
	 *   <li>그 밖의 예외는 문장이 반영됐는지 알 수 없으므로 UNKNOWN. 운영 SELECT 실패도 여기 포함되는데,
	 *       실제로는 미반영이지만 보수적으로 보류한다(잘못 재시도하는 쪽이 더 위험하다)</li>
	 * </ul>
	 */
	private ApplyOutcome applyToServing(ServingWriter servingWriter, ChangeProposal proposal,
			LocationEvidence evidence, String verifiedExternalId) {
		boolean exactManifest = verifiedExternalId != null;
		String target = proposal.getTarget();
		long storeId = proposal.getStoreId();
		// menu.{id}.removed 는 new_value 가 NULL 이다 (소멸 제안) — null 안전 파싱.
		// 파싱은 첫 DML 보다 먼저 하고, 실패를 ProposalException 으로 감싸 FAILED(운영 미반영)로 확정한다
		JsonNode newValue = parseValue(proposal.getNewValue(), "new_value");
		JsonNode oldValue = parseValue(proposal.getOldValue(), "old_value");

		if ("store.name".equals(target)) {
			// 개명 전 구명을 alias 로 보존 — 옛 이름 검색 유지 (본 앱 pg_bigm 이 alias 를 탄다).
			// UPDATE 와 alias INSERT 는 한 문장이라 반쪽 반영이 없다
			String oldName = textOrNull(oldValue);
			String preservedAlias = oldName != null && !oldName.isBlank() ? oldName : null;
			if (exactManifest) return requireApplied(target,
				servingWriter.updateStoreName(storeId,verifiedExternalId,oldName,newValue.asString(),preservedAlias),"name");
			return requireApplied(target,
				servingWriter.updateStoreName(storeId, oldName, newValue.asString(), preservedAlias), "name");
		}
		if ("store.phone".equals(target)) {
			if (exactManifest) return requireApplied(target,
				servingWriter.updateStorePhone(storeId,verifiedExternalId,textOrNull(oldValue),newValue.asString()),"phone");
			return requireApplied(target,
				servingWriter.updateStorePhone(storeId, textOrNull(oldValue), newValue.asString()), "phone");
		}
		if ("store.naver_place_id".equals(target)) {
			// old_value 는 보통 NULL — 매칭 선점 방지가 이 조건이다 (그새 다른 경로가 매칭했으면 0행)
			return requireApplied(target,
				servingWriter.updateStoreNaverPlaceId(storeId, textOrNull(oldValue), newValue.asString()),
				"naver_place_id");
		}
		if ("store.status".equals(target)) {
			// 폐업·중복 정리 — old_value 는 제안 당시 상태(보통 "ACTIVE")
			return requireApplied(target, servingWriter.softDeleteStore(storeId, textOrNull(oldValue)), "status");
		}
		if ("store.instagram_handle".equals(target)) {
			if (exactManifest) return requireApplied(target,
				servingWriter.updateStoreInstagram(storeId,verifiedExternalId,textOrNull(oldValue),newValue.asString()),"instagram_handle");
			return requireApplied(target,
				servingWriter.updateStoreInstagram(storeId, textOrNull(oldValue), newValue.asString()),
				"instagram_handle");
		}
		if ("store.address".equals(target)) {
			// 주소·좌표·지역은 한 문장. 지역은 게이트가 찾은 새 좌표의 법정동 근거에서 온다 — 근거 없이는 여기 오지 않는다
			requireEvidence(target, evidence);
			if (exactManifest) return requireApplied(target, servingWriter.updateObservedStoreAddress(storeId, verifiedExternalId,
				textOrNull(oldValue.path("address")), oldValue.path("lon").asDouble(), oldValue.path("lat").asDouble(),
				newValue.path("address").asString(), newValue.path("lon").asDouble(), newValue.path("lat").asDouble(),
				evidence.region1st(), evidence.region2nd(), evidence.region3rd()), "address");
			return requireApplied(target, servingWriter.updateStoreAddress(storeId,
				textOrNull(oldValue.path("address")),
				newValue.path("address").asString(null),
				newValue.path("lon").isNumber() ? newValue.path("lon").asDouble() : null,
				newValue.path("lat").isNumber() ? newValue.path("lat").asDouble() : null,
				evidence.region1st(), evidence.region2nd(), evidence.region3rd()), "address");
		}
		if ("store.region".equals(target)) {
			// 게이트가 제안 지역 == 현재 좌표의 근거 지역을 확인했다 — 쓰는 값은 근거 쪽이다
			requireEvidence(target, evidence);
			return requireApplied(target, servingWriter.updateStoreRegion(storeId,
				textOrNull(oldValue.path("region_1st")),
				textOrNull(oldValue.path("region_2nd")),
				textOrNull(oldValue.path("region_3rd")),
				evidence.region1st(), evidence.region2nd(), evidence.region3rd()), "region");
		}
		if ("store.category".equals(target)) {
			// 네이버 카테고리 → BE StoreCategory 코드. 제안은 store_category_propose.py, 승인 규칙은 approve_stage3_pending.py(category)
			return requireApplied(target,
				servingWriter.updateStoreCategory(storeId, textOrNull(oldValue), newValue.asString()), "category");
		}
		if ("store.business_hours".equals(target)) {
			if (exactManifest) return requireApplied(target,
				servingWriter.replaceBusinessHours(storeId,verifiedExternalId,hoursRows(oldValue),hoursRows(newValue)),"business_hours");
			// 제안 당시 운영 세트(old_value)가 그대로일 때만 바꾼다 — 그새 바뀌었거나 DELETED 면 0행, 운영 미반영(FAILED)
			return requireApplied(target,
				servingWriter.replaceBusinessHours(storeId, hoursRows(oldValue), hoursRows(newValue)), "business_hours");
		}
		if (target.startsWith(MENU_ADD_TARGET_PREFIX)) {
			String menuName = newValue.path("name").asString();
			Integer menuPrice = newValue.path("price").isNumber() ? newValue.path("price").asInt() : null;
			String description = newValue.path("description").isNull() ? null : newValue.path("description").asString(null);
			String category = menuCategoryClassifier.classify(menuName);
			MenuUpsertResult result = exactManifest
				? servingWriter.insertObservedMenu(storeId, verifiedExternalId, menuName, menuPrice, description, category)
				: proposal.getSource() == ProvenanceSource.BAEMIN
				? servingWriter.upsertMenu(storeId, menuName, menuPrice, description, category, false)
				: servingWriter.upsertMenu(storeId, menuName, menuPrice, description, category);
			if (result.outcome() == MenuUpsertResult.Outcome.BLOCKED_EXISTING_MENU)
				throw new ProposalException(ProposalErrorCode.SERVING_VALUE_DRIFTED, target);

			if (result.blocked()) {
				ProposalErrorCode code = result.outcome() == MenuUpsertResult.Outcome.BLOCKED_USER_MENU
					? ProposalErrorCode.MENU_CONFLICTS_USER_MENU
					: ProposalErrorCode.MENU_CONFLICTS_DISTINCT_NAME;
				throw new ProposalException(code, "menu." + result.menuId() + " ← " + menuName);
			}
			String note = result.updated()
				// 정규화 차이로 "새 메뉴"로 제안됐지만 운영에 이미 있던 메뉴 — 삽입 대신 가격·설명을 갱신했다
				? "기존 메뉴 #" + result.menuId() + " 갱신 (가격 " + result.previousPrice() + "→" + menuPrice + ")"
				: null;
			return new ApplyOutcome("menu." + result.menuId(), note);
		}
		Matcher price = MENU_PRICE_TARGET.matcher(target);
		if (price.matches()) {
			Integer expectedPrice = oldValue.isNumber() ? oldValue.asInt() : null;
			if (exactManifest) return requireApplied(target,servingWriter.updateMenuPrice(
				Long.parseLong(price.group(1)),storeId,verifiedExternalId,expectedPrice,newValue.asInt()),target);
			return requireApplied(target, servingWriter.updateMenuPrice(
				Long.parseLong(price.group(1)), expectedPrice, newValue.asInt()), target);
		}
		Matcher description = MENU_DESCRIPTION_TARGET.matcher(target);
		if (description.matches()) {
			if (exactManifest) return requireApplied(target,servingWriter.updateMenuDescription(
				Long.parseLong(description.group(1)),storeId,verifiedExternalId,textOrNull(oldValue),newValue.asString()),target);
			return requireApplied(target, servingWriter.updateMenuDescription(
				Long.parseLong(description.group(1)), textOrNull(oldValue), newValue.asString()), target);
		}
		Matcher category = MENU_CATEGORY_TARGET.matcher(target);
		if (category.matches()) {
			// 정책 재판정의 재분류 — old_value 의 categoryId(NULL 포함)가 운영에 그대로일 때만 바꾼다
			Long expectedCategoryId = oldValue.path("categoryId").isNumber() ? oldValue.path("categoryId").asLong() : null;
			int updated = exactManifest ? servingWriter.updateObservedMenuCategory(Long.parseLong(category.group(1)), storeId,
				verifiedExternalId, expectedCategoryId, newValue.path("category").asString())
				: servingWriter.updateMenuCategory(Long.parseLong(category.group(1)), expectedCategoryId, newValue.path("category").asString());
			if (updated == 0) {
				throw new ProposalException(ProposalErrorCode.MENU_CATEGORY_CHANGED, target);
			}
			return ApplyOutcome.of(target);
		}
		Matcher removed = MENU_REMOVED_TARGET.matcher(target);
		if (removed.matches()) {
			if (exactManifest) return requireApplied(target, servingWriter.softDeleteObservedMenu(Long.parseLong(removed.group(1)),
				storeId, verifiedExternalId, textOrNull(oldValue.path("name")),
				oldValue.path("price").isNumber() ? oldValue.path("price").asInt() : null,
				textOrNull(oldValue.path("description"))), "menu." + removed.group(1));
			// old_value 는 소멸 관측 당시의 메뉴 — 이름이 바뀐 행은 다른 상품으로 재사용된 것일 수 있어 지우지 않는다
			return requireApplied(target, servingWriter.softDeleteMenu(
				Long.parseLong(removed.group(1)), textOrNull(oldValue.path("name"))),
				"menu." + removed.group(1));
		}
		throw new ProposalException(ProposalErrorCode.PROPOSAL_TARGET_UNSUPPORTED, target);
	}

	/**
	 * 위치 제안은 게이트의 근거 없이 쓰지 않는다 — 방어선이다. 정상 흐름에서는 게이트가 점유 전에 막으므로 닿지 않는다.
	 * 닿으면 점유 뒤라 FAILED(운영 미반영)로 확정된다 — 운영 DML 전에 던지므로 미반영은 사실이다.
	 */
	private void requireEvidence(String target, LocationEvidence evidence) {
		if (evidence == null) {
			throw new ProposalException(ProposalErrorCode.LOCATION_EVIDENCE_REQUIRED, target + " — 지역 근거 없음");
		}
	}

	/** apply_log 에 실을 새 값 — 위치 제안이면 실제로 쓸 지역과 근거 식별자를 더한다 */
	private String withEvidence(String newValue, LocationEvidence evidence) {
		if (evidence == null) {
			return newValue;
		}
		tools.jackson.databind.node.ObjectNode node = parseValue(newValue, "new_value").isObject()
			? (tools.jackson.databind.node.ObjectNode)parseValue(newValue, "new_value")
			: objectMapper.createObjectNode();
		node.put("region_1st", evidence.region1st());
		node.put("region_2nd", evidence.region2nd());
		node.put("region_3rd", evidence.region3rd());
		node.put("bjd_code", evidence.bjdCode());
		node.put("region_resolution_id", evidence.resolutionId());
		node.put("region_reference_version", evidence.referenceVersion());
		return objectMapper.writeValueAsString(node);
	}

	/**
	 * 조건부 쓰기의 영향 행 수 검사 — 0행은 운영 값이 제안 당시와 다르다는 뜻이고, 운영에는 아무것도 쓰지 않았다.
	 * 여기서 던지는 예외는 호출자가 FAILED(운영 미반영 확정)로 기록한다. 덮어쓰기로 되돌리지 않는다.
	 */
	/** 영업시간 제안 값(JSON 배열 [{day, open, close, closed}]) → 행. 배열이 아니면(빈 old_value) 빈 세트 */
	private List<BusinessHourRow> hoursRows(JsonNode value) {
		List<BusinessHourRow> rows = new ArrayList<>();
		if (value == null || !value.isArray()) {
			return rows;
		}
		for (JsonNode row : value) {
			rows.add(new BusinessHourRow(
				row.path("day").asString(),
				row.path("open").isNull() ? null : row.path("open").asString(null),
				row.path("close").isNull() ? null : row.path("close").asString(null),
				row.path("closed").asBoolean(false)));
		}
		return rows;
	}

	private ApplyOutcome requireApplied(String target, int affectedRows, String provenanceField) {
		if (affectedRows == 0) {
			throw new ProposalException(ProposalErrorCode.SERVING_VALUE_DRIFTED, target);
		}
		return new ApplyOutcome(provenanceField, null);
	}

	/**
	 * 예상 이전 값의 문자열 추출 — null/missing 을 문자열 "null" 로 만들지 않는다.
	 * CAS 조건에 "null" 이 들어가면 NULL 이던 필드가 절대 매칭되지 않아 모든 승인이 0행이 된다.
	 */
	private static String textOrNull(JsonNode node) {
		return node == null || node.isNull() || node.isMissingNode() ? null : node.asString(null);
	}

	/** 제안 값(JSONB) 해석 — 실패를 운영 미반영 확정(FAILED)으로 기록하기 위해 감싼다 */
	private JsonNode parseValue(String raw, String column) {
		if (raw == null) {
			return NullNode.getInstance();
		}
		try {
			return objectMapper.readTree(raw);
		} catch (RuntimeException e) {
			throw new ProposalException(ProposalErrorCode.PROPOSAL_VALUE_INVALID, column + " — " + e.getMessage());
		}
	}

	private ChangeProposal getProposal(Long proposalId) {
		return changeProposalRepository.findById(proposalId)
			.orElseThrow(() -> new ProposalException(ProposalErrorCode.PROPOSAL_NOT_FOUND));
	}
}
