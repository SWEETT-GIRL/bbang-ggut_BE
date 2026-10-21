package sweetgirl.dataops.snapshot.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.dataops.snapshot.dto.response.ParsedPlaceResponse;
import sweetgirl.dataops.snapshot.entity.PlaceSnapshot;
import sweetgirl.dataops.snapshot.exception.SnapshotException;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.repository.PlaceSnapshotRepository;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SnapshotQueryServiceTest {

	@Mock
	private PlaceSnapshotRepository placeSnapshotRepository;

	private SnapshotQueryService snapshotQueryService;

	@BeforeEach
	void setUp() {
		// 파싱 미리보기는 실제 파서·ObjectMapper 로 검증한다 (눈검사 도구 자체가 틀리면 안 되므로)
		snapshotQueryService = new SnapshotQueryService(
			placeSnapshotRepository, new ObjectMapper(), new NaverPlaceParser());
	}

	private PlaceSnapshot createSnapshot(String payload) {
		return PlaceSnapshot.builder()
			.storeId(1L)
			.source(SyncSource.NAVER)
			.fetchedAt(LocalDateTime.now())
			.contentHash("a".repeat(64))
			.payload(payload)
			.build();
	}

	@Test
	void getParsedSnapshot_저장된_원본을_실제_파서로_파싱한다() {
		String payload = """
				{"PlaceDetailBase:1": {"__typename": "PlaceDetailBase", "name": "빵집", "phone": "02-1-2"},
				 "Menu:1_0": {"__typename": "Menu", "name": "소금빵", "price": "2900",
				              "description": null, "images": []}}""";
		given(placeSnapshotRepository.findById(10L)).willReturn(Optional.of(createSnapshot(payload)));

		ParsedPlaceResponse response = snapshotQueryService.getParsedSnapshot(10L);

		assertThat(response.name()).isEqualTo("빵집");
		assertThat(response.menus()).hasSize(1);
		assertThat(response.menus().get(0).name()).isEqualTo("소금빵");
	}

	@Test
	void getParsedSnapshot_없는_스냅샷은_예외를_던진다() {
		given(placeSnapshotRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> snapshotQueryService.getParsedSnapshot(99L))
			.isInstanceOf(SnapshotException.class);
	}
}
