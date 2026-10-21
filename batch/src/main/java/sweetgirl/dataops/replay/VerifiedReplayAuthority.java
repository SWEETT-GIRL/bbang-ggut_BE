package sweetgirl.dataops.replay;

import java.util.UUID;

/** No default implementation or HTTP entry point: absent authenticated approval fails closed. */
public interface VerifiedReplayAuthority {
	ReplayPlan require(UUID operationId, String manifestDigest);
	/** Adapter must measure the running artifact, not repeat the caller's requested value. */
	RuntimeIdentity currentRuntime();
	/** A data apply holds this shared service lock through the last serving write and settlement. */
	interface ApplyScope extends AutoCloseable {
		void assertHeld();
		@Override void close();
	}
	default ApplyScope openApplyScope() { throw new IllegalStateException("TRUSTED_APPLY_SCOPE_REQUIRED"); }
	record RuntimeIdentity(String releaseId, String artifactDigest, String servingDatabaseIdentity) {
		public RuntimeIdentity(String releaseId, String artifactDigest) { this(releaseId,artifactDigest,null); }
	}
}
