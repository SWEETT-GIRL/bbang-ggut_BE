package sweetgirl.dataops.agentapply;

/** A public reason code; no raw source, credentials or arbitrary SQL errors. */
public class AgentApplyHold extends RuntimeException {
	public AgentApplyHold(String reason) { super(reason); }
}
