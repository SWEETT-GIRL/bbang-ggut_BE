package sweetgirl.dataops.autoapply;

import sweetgirl.dataops.reconcile.enums.ProvenanceSource;

/** Values judged from this capture, rather than whatever happens to be PENDING later. */
public record AutomaticCandidate(long proposalId, String target, String oldValue, String newValue,
		ProvenanceSource source) {
}
