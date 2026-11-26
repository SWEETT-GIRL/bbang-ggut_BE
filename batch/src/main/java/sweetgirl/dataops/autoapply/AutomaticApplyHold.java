package sweetgirl.dataops.autoapply;

public class AutomaticApplyHold extends RuntimeException {
	public AutomaticApplyHold(String reason) { super(reason); }
}
