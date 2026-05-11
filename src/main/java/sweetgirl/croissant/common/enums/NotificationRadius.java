package sweetgirl.croissant.common.enums;

public enum NotificationRadius {
	RADIUS_30(30),
	RADIUS_50(50),
	RADIUS_70(70);

	private final int meters;

	NotificationRadius(int meters) {
		this.meters = meters;
	}

	public int getMeters() {
		return meters;
	}
}
