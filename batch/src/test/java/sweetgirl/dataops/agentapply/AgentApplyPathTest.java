package sweetgirl.dataops.agentapply;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.exception.ProposalException;
import sweetgirl.dataops.proposal.policy.LocationProposalGate;
import sweetgirl.dataops.proposal.policy.MenuRemovalProposalGate;
import sweetgirl.dataops.proposal.repository.ChangeProposalRepository;
import sweetgirl.dataops.proposal.service.ProposalApplyStateService;
import sweetgirl.dataops.proposal.service.ProposalCommandService;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.reconcile.policy.MenuCategoryClassifier;
import sweetgirl.dataops.reconcile.repository.StoreFieldProvenanceRepository;
import sweetgirl.dataops.replay.VerifiedReplayAuthority;
import sweetgirl.dataops.serving.client.ServingWriter;
import sweetgirl.dataops.serving.service.ApplyLogCommandService;
import sweetgirl.dataops.snapshot.concurrency.StoreObservationBarrier;
import tools.jackson.databind.ObjectMapper;

class AgentApplyPathTest {
	private final AgentApplyRequest request = new AgentApplyRequest("a".repeat(64),UUID.randomUUID());
	private ChangeProposal proposal;
	private ChangeProposalRepository repository;
	private ProposalApplyStateService state;
	private AgentManifestApplyGate gate;
	private ServingWriter writer;
	private StoreObservationBarrier barrier;
	private StoreObservationBarrier.ApprovalScope scope;
	private ProposalCommandService service;

	@BeforeEach @SuppressWarnings("unchecked") void setup() {
		proposal=ChangeProposal.builder().storeId(1L).target("store.phone").oldValue("\"old\"")
			.newValue("\"new\"").source(ProvenanceSource.NAVER).build();
		ReflectionTestUtils.setField(proposal,"id",5L);
		repository=mock(ChangeProposalRepository.class); state=mock(ProposalApplyStateService.class);
		gate=mock(AgentManifestApplyGate.class); writer=mock(ServingWriter.class);
		barrier=mock(StoreObservationBarrier.class); scope=mock(StoreObservationBarrier.ApprovalScope.class);
		when(repository.findById(5L)).thenReturn(Optional.of(proposal));
		when(barrier.lockApproval(1L)).thenReturn(scope);
		when(state.claimExactManifest(5L,request)).thenAnswer(call->{UUID op=UUID.randomUUID();proposal.beginApply(op);return op;});
		when(gate.verifyBeforeWrite(proposal,request)).thenReturn("123");
		when(writer.updateStorePhone(1L,"123","old","new")).thenReturn(1);
		doAnswer(call->{proposal.completeApply(null);return null;}).when(state).settleApplied(eq(5L),anyString(),any());
		service=new ProposalCommandService(repository,state,mock(ApplyLogCommandService.class),mock(ObjectProvider.class),
			mock(ObjectProvider.class),mock(LocationProposalGate.class),mock(MenuRemovalProposalGate.class),barrier,
			new ObjectMapper(),new MenuCategoryClassifier());
		service.configureManifestApplyGate(gate);
	}

	@Test void normal_approval_and_direct_unscoped_claim_are_blocked_only_in_manifest_mode() {
		assertThatThrownBy(()->service.approve(5L)).isInstanceOf(AgentApplyHold.class);
		verifyNoInteractions(writer);
		var realState=new ProposalApplyStateService(repository,mock(StoreFieldProvenanceRepository.class));
		realState.configureManifestApplyGate(gate);
		assertThatThrownBy(()->realState.claim(5L)).isInstanceOf(AgentApplyHold.class);
		verify(repository,never()).claimForApply(any(),any(),any());
	}

	@Test @SuppressWarnings("unchecked") void replay_created_proposals_cannot_use_the_plain_writer_service_without_agent_mode() {
		var realState=new ProposalApplyStateService(repository,mock(StoreFieldProvenanceRepository.class));
		when(repository.requiresAgentManifest(5L)).thenReturn(true);
		ObjectProvider<ServingWriter> writers=mock(ObjectProvider.class);
		when(writers.getIfAvailable()).thenReturn(writer);
		var plain=new ProposalCommandService(repository,realState,mock(ApplyLogCommandService.class),writers,
			mock(ObjectProvider.class),mock(LocationProposalGate.class),mock(MenuRemovalProposalGate.class),barrier,
			new ObjectMapper(),new MenuCategoryClassifier());
		assertThatThrownBy(()->plain.approve(5L)).hasMessage("AGENT_CREATED_PROPOSAL_REQUIRES_MANIFEST");
		verify(repository,never()).claimForApply(any(),any(),any());
		verifyNoInteractions(writer);
	}

	@Test void original_manual_authority_of_a_reused_proposal_is_unchanged() {
		var realState=new ProposalApplyStateService(repository,mock(StoreFieldProvenanceRepository.class));
		when(repository.requiresAgentManifest(5L)).thenReturn(false);
		when(repository.claimForApply(eq(5L),any(),any())).thenReturn(1);
		assertThat(realState.claim(5L)).isNotNull();
		verify(repository).claimForApply(eq(5L),any(),any());
	}

	@Test void another_unknown_operation_blocks_the_common_claim_regardless_of_approval_mode() {
		var realState=new ProposalApplyStateService(repository,mock(StoreFieldProvenanceRepository.class));
		when(repository.hasUnresolvedTarget(5L)).thenReturn(true);
		assertThatThrownBy(()->realState.claim(5L)).isInstanceOf(ProposalException.class);
		realState.configureManifestApplyGate(gate);
		when(repository.findForApplyUpdate(5L)).thenReturn(Optional.of(proposal));
		assertThatThrownBy(()->realState.claimExactManifest(5L,request)).isInstanceOf(ProposalException.class);
		verify(repository,never()).claimForApply(any(),any(),any());
	}

	@Test void unknown_target_is_not_superseded_by_another_producer() {
		proposal.beginApply(UUID.randomUUID());proposal.markApplyUnknown("SERVING_ERROR","lost response");
		when(repository.findFirstByStoreIdAndTargetAndStatusIn(eq(1L),eq("store.phone"),any()))
			.thenReturn(Optional.of(proposal));
		assertThat(service.propose(1L,"store.phone","\"old\"","\"different\"",ProvenanceSource.NAVER,null)).isNull();
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.UNKNOWN);
		verify(repository,never()).save(any());
	}

	@Test @SuppressWarnings("unchecked") void reconciliation_without_actual_evidence_authority_is_closed() {
		var resolver=new sweetgirl.dataops.proposal.service.ProposalReconciliationService(repository,state,mock(ObjectProvider.class),
			mock(javax.sql.DataSource.class),new ObjectMapper());
		assertThatThrownBy(()->resolver.resolve(5L,UUID.randomUUID())).hasMessage("TRUSTED_RECONCILIATION_AUTHORITY_REQUIRED");
		verify(repository,never()).findForApplyUpdate(any());
	}

	@Test void nonmenu_write_holds_observation_barrier_through_exact_claim_and_write() {
		service.approveExactManifest(5L,request,writer);
		var ordered=inOrder(barrier,gate,state,writer,scope);
		ordered.verify(barrier).lockApproval(1L);
		ordered.verify(gate).verifyBeforeWrite(proposal,request);
		ordered.verify(scope).assertHeld();
		ordered.verify(state).claimExactManifest(5L,request);
		ordered.verify(scope).assertHeld();
		ordered.verify(gate).verifyBeforeWrite(proposal,request);
		ordered.verify(writer).updateStorePhone(1L,"123","old","new");
		ordered.verify(state).settleApplied(5L,"phone",null);
		ordered.verify(scope).close();
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.APPROVED);
		verify(state,never()).claim(any());
	}

	@Test void approved_hours_use_only_the_exact_transactional_overload() {
		ReflectionTestUtils.setField(proposal,"target","store.business_hours");
		ReflectionTestUtils.setField(proposal,"oldValue","[]");
		ReflectionTestUtils.setField(proposal,"newValue","[{\"day\":\"MONDAY\",\"open\":\"09:00\",\"close\":\"18:00\",\"closed\":false}]");
		when(writer.replaceBusinessHours(eq(1L),eq("123"),anyList(),anyList())).thenReturn(1);
		service.approveExactManifest(5L,request,writer);
		verify(writer).replaceBusinessHours(eq(1L),eq("123"),anyList(),anyList());
		verify(writer,never()).replaceBusinessHours(anyLong(),anyList(),anyList());
	}

	@Test void changed_approval_after_claim_stops_before_dml_and_marks_known_nonapplication() {
		var calls=new AtomicInteger();
		doAnswer(call->{if(calls.incrementAndGet()==2)throw new AgentApplyHold("RUNNING_ARTIFACT_CHANGED");return "123";})
			.when(gate).verifyBeforeWrite(proposal,request);
		assertThatThrownBy(()->service.approveExactManifest(5L,request,writer)).isInstanceOf(AgentApplyHold.class);
		verifyNoInteractions(writer);
		verify(state).settleFailed(5L,"AGENT_APPROVAL_HELD","RUNNING_ARTIFACT_CHANGED");
		verify(scope).close();
	}

	@Test void serving_current_value_conflict_still_uses_existing_cas_failure_path() {
		when(writer.updateStorePhone(1L,"123","old","new")).thenReturn(0);
		assertThatThrownBy(()->service.approveExactManifest(5L,request,writer)).isInstanceOf(ProposalException.class);
		verify(state).settleFailed(eq(5L),eq("VALUE_DRIFTED"),anyString());
		verify(scope).close();
	}

	@Test void already_applied_exact_item_is_returned_without_a_second_write() {
		when(gate.alreadyApplied(proposal,request)).thenReturn(true);
		service.approveExactManifest(5L,request,writer);
		verifyNoInteractions(writer);
		verify(state,never()).claimExactManifest(any(),any());
		verify(barrier,never()).lockApproval(anyLong());
	}

	@Test void row_lock_gate_and_claim_share_one_state_method_and_order() {
		var realState=new ProposalApplyStateService(repository,mock(StoreFieldProvenanceRepository.class));
		realState.configureManifestApplyGate(gate);
		when(repository.findForApplyUpdate(5L)).thenReturn(Optional.of(proposal));
		when(repository.claimForApply(eq(5L),any(),any())).thenReturn(1);
		UUID op=realState.claimExactManifest(5L,request);
		var ordered=inOrder(repository,gate);
		ordered.verify(repository).findForApplyUpdate(5L);
		ordered.verify(gate).verifyClaim(proposal,request);
		ordered.verify(repository).claimForApply(eq(5L),eq(op),any());
		ordered.verify(gate).recordClaim(proposal,request,op);
	}

	@Test @SuppressWarnings("unchecked") void standalone_runner_requires_real_attestor_before_opening_private_writer() {
		var env=new MockEnvironment().withProperty("spring.main.web-application-type","none")
			.withProperty("dataops.reconcile.mode","PROPOSAL_ONLY");
		ObjectProvider<VerifiedReplayAuthority> missing=mock(ObjectProvider.class);
		var runner=new AgentManifestApplyRunner(env,mock(ApplicationContext.class),gate,service,missing);
		assertThatThrownBy(()->runner.run(mock(ApplicationArguments.class)))
			.isInstanceOf(AgentApplyHold.class).hasMessage("TRUSTED_RUNTIME_ATTESTOR_REQUIRED");
		verify(gate,never()).begin(any());
	}

	@Test @SuppressWarnings("unchecked") void standalone_runner_closes_shared_release_scope_when_identity_verification_fails() {
		var env=new MockEnvironment().withProperty("spring.main.web-application-type","none")
			.withProperty("dataops.reconcile.mode","PROPOSAL_ONLY");
		ObjectProvider<VerifiedReplayAuthority> provider=mock(ObjectProvider.class);
		var attestor=mock(VerifiedReplayAuthority.class);var applyScope=mock(VerifiedReplayAuthority.ApplyScope.class);
		when(provider.getIfAvailable()).thenReturn(attestor);when(attestor.openApplyScope()).thenReturn(applyScope);
		when(attestor.currentRuntime()).thenReturn(new VerifiedReplayAuthority.RuntimeIdentity("release","a".repeat(64)));
		var runner=new AgentManifestApplyRunner(env,mock(ApplicationContext.class),gate,service,provider);
		assertThatThrownBy(()->runner.run(mock(ApplicationArguments.class))).hasMessage("TRUSTED_SERVING_IDENTITY_REQUIRED");
		var ordered=inOrder(attestor,applyScope);
		ordered.verify(attestor).openApplyScope();ordered.verify(applyScope).assertHeld();
		ordered.verify(attestor).currentRuntime();ordered.verify(applyScope).close();
		verify(gate,never()).begin(any());
	}
}
