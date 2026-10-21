package sweetgirl.croissant.notification.event;

import java.time.Instant;
import java.util.List;

public record OperationalNotificationEvent(
		Channel channel,
		String title,
		String description,
		int color,
		Instant timestamp,
		List<Field> fields) {

	public OperationalNotificationEvent(
			Channel channel,
			String title,
			String description,
			int color,
			List<Field> fields) {
		this(channel, title, description, color, Instant.now(), List.copyOf(fields));
	}

	public enum Channel {
		REPORT,
		ABUSE,
		LETTER
	}

	public record Field(String name, String value, boolean inline) {
	}
}
