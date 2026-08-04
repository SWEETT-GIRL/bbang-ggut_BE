package sweetgirl.croissant.breadlog.service;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import sweetgirl.croissant.store.RegionFormatter;
import sweetgirl.croissant.store.entity.Store;

@Component
public class BreadRecordRegionResolver {

	public String resolve(Store store, String requestedRegionKey) {
		if (store == null) {
			return normalizeNullable(requestedRegionKey);
		}
		String regionKey = Stream.of(
			RegionFormatter.shortSido(store.getRegion1st()),
			store.getRegion2nd(),
			store.getRegion3rd())
			.filter(part -> part != null && !part.isBlank())
			.map(String::trim)
			.collect(Collectors.joining("_"));
		return regionKey.isBlank() ? null : regionKey;
	}

	private String normalizeNullable(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.strip();
	}
}
