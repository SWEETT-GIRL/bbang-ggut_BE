package sweetgirl.dataops.agentapply;

import java.util.UUID;

/** Identifiers only. The database and runtime attestor, never these arguments, supply authority. */
public record AgentApplyRequest(String approvalDigest, UUID operationId, String servingDatabaseIdentity) {
	public AgentApplyRequest(String approvalDigest, UUID operationId) { this(approvalDigest,operationId,null); }
	public AgentApplyRequest {
		if (approvalDigest == null || !approvalDigest.matches("[0-9a-f]{64}") || operationId == null)
			throw new IllegalArgumentException("Exact authenticated approval required");
		if (servingDatabaseIdentity != null && !servingDatabaseIdentity.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("Measured serving identity must be a SHA-256 digest");
	}
}
