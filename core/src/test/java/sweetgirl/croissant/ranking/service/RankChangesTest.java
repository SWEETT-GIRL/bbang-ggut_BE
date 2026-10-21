package sweetgirl.croissant.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RankChangesTest {

	@Test
	@DisplayName("어제 등수 − 오늘 등수: 올랐으면 양수, 내렸으면 음수, 같으면 0, 어제 없었으면 NEW(변동 null)")
	void knownChanges() {
		RankChanges changes = RankChanges.of(Map.of(1L, 3, 2L, 1, 3L, 2));

		assertThat(changes.changeOf(1L, 1)).isEqualTo(2);
		assertThat(changes.changeOf(2L, 3)).isEqualTo(-2);
		assertThat(changes.changeOf(3L, 2)).isZero();
		assertThat(changes.changeOf(9L, 4)).isNull();
		assertThat(changes.isNew(9L)).isTrue();
		assertThat(changes.isNew(1L)).isFalse();
	}

	@Test
	@DisplayName("어제 스냅샷을 모르면 변동도 NEW 도 null")
	void unknownChanges() {
		RankChanges changes = RankChanges.unknown();

		assertThat(changes.changeOf(1L, 1)).isNull();
		assertThat(changes.isNew(1L)).isNull();
	}
}
