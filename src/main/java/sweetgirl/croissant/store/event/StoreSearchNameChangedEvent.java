package sweetgirl.croissant.store.event;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 가게가 생기거나, 이름이 바뀌거나, 노출 여부(ACTIVE ↔ DELETED)가 바뀌어 자동완성 결과가 달라질 수 있다.
 *
 * @param names 영향받는 이름 — 이름이 바뀌면 바뀌기 전·후 둘 다 넣는다(옛 이름으로 캐시된 결과도 지워야 한다)
 */
public record StoreSearchNameChangedEvent(List<String> names) {

	public static StoreSearchNameChangedEvent of(String... names) {
		return new StoreSearchNameChangedEvent(Arrays.stream(names).filter(Objects::nonNull).distinct().toList());
	}
}
