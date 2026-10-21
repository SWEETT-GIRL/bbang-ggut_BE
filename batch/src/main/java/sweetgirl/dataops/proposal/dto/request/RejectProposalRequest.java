package sweetgirl.dataops.proposal.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectProposalRequest(@NotBlank @Size(max = 500) String note) {
}
