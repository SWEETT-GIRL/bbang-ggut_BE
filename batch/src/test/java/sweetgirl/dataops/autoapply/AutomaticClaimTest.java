package sweetgirl.dataops.autoapply;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.repository.ChangeProposalRepository;
import sweetgirl.dataops.proposal.service.ProposalApplyStateService;
import sweetgirl.dataops.reconcile.repository.StoreFieldProvenanceRepository;

class AutomaticClaimTest {
	@Test void checks_locked_candidate_before_claim_and_never_claims_a_conflict() {
		var repository = mock(ChangeProposalRepository.class);
		var service = new ProposalApplyStateService(repository, mock(StoreFieldProvenanceRepository.class));
		when(repository.findForApplyUpdate(7L)).thenReturn(Optional.of(mock(ChangeProposal.class)));
		assertThatThrownBy(() -> service.claimAutomatic(7L, candidate -> { throw new AutomaticApplyException.Hold(AutomaticApplyException.Reason.SOURCE_CONFLICT); }))
			.hasMessage("SOURCE_CONFLICT");
		verify(repository, never()).claimForApply(anyLong(), any(), any());
		when(repository.claimForApply(eq(7L), any(), any())).thenReturn(1);
		assertThat(service.claimAutomatic(7L, candidate -> {})).isNotNull();
	}
	@Test void automatic_claim_does_not_bypass_agent_manifest_approval() {
		var repository = mock(ChangeProposalRepository.class);
		when(repository.requiresAgentManifest(7L)).thenReturn(true);
		var service = new ProposalApplyStateService(repository, mock(StoreFieldProvenanceRepository.class));
		assertThatThrownBy(() -> service.claimAutomatic(7L, candidate -> {})).hasMessage("AGENT_APPROVAL_REQUIRED");
		verify(repository, never()).claimForApply(anyLong(), any(), any());
	}
}
