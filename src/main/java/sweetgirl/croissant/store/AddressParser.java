package sweetgirl.croissant.store;

public final class AddressParser {

	private AddressParser() {

	}

	public static String[] parse(String addressName) {
		if (addressName == null || addressName.isBlank()) {
			return new String[] {null, null, null};
		}
		String[] parts = addressName.trim().split("\\s+");
		return new String[] {
			parts.length > 0 ? parts[0] : null,
			parts.length > 1 ? parts[1] : null,
			parts.length > 2 ? parts[2] : null
		};
	}
}
