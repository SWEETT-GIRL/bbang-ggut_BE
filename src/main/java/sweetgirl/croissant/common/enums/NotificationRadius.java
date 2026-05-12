package sweetgirl.croissant.common.enums;

public enum NotificationRadius {
	RADIUS_100(100),
	RADIUS_300(300),
	RADIUS_500(500),
	RADIUS_700(700),
	RADIUS_1000(1000);

	private final int meters;

	NotificationRadius(int meters) {
		this.meters = meters;
	}

	public int getMeters() {
		return meters;
	}
}
