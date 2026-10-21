package sweetgirl.dataops.matching.dto.response;

import java.util.List;

public record MatchingProposalResult(int requested, int created, int skipped, List<Long> proposalIds) {
}
