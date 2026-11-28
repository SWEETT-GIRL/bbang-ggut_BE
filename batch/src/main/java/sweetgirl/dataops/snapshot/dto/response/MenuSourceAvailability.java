package sweetgirl.dataops.snapshot.dto.response;

/** Source availability is independent of serving writes and never authorizes deletion. */
public record MenuSourceAvailability(int version, State naver, State baemin) {

	public enum State { AVAILABLE, EMPTY, NOT_PROVIDED, INCOMPLETE, UNVERIFIED }

	public MenuSourceAvailability(State naver, State baemin) { this(1, naver, baemin); }
	public static MenuSourceAvailability unverified() {
		return new MenuSourceAvailability(State.UNVERIFIED, State.UNVERIFIED);
	}
}
