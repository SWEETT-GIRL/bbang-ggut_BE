package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

@ExtendWith(MockitoExtension.class)
class StoreThumbnailReaderTest {

	@Mock
	private StoreImageFileRepository storeImageFileRepository;

	@InjectMocks
	private StoreThumbnailReader reader;

	@Test
	@DisplayName("320 리사이즈가 있으면 그것, 없으면 원본 키 — 가게당 첫 사진만")
	void prefersResizedKey() {
		List<StoreImageThumbnailProjection> projections = List.of(
			projection(1L, "resized/320/a", "a"), projection(1L, null, "b"), projection(2L, null, "c"));
		given(storeImageFileRepository.findThumbnailsByStoreIdIn(List.of(1L, 2L))).willReturn(projections);

		Map<Long, String> thumbnails = reader.thumbnailsOf(List.of(1L, 2L, 1L));

		assertThat(thumbnails).containsEntry(1L, "resized/320/a").containsEntry(2L, "c");
	}

	@Test
	@DisplayName("가게가 없으면 묻지 않는다")
	void emptyIds() {
		assertThat(reader.thumbnailsOf(List.of())).isEmpty();
		verify(storeImageFileRepository, never()).findThumbnailsByStoreIdIn(any());
	}

	private static StoreImageThumbnailProjection projection(Long storeId, String resized, String awsKey) {
		StoreImageThumbnailProjection projection = mock(StoreImageThumbnailProjection.class);
		lenient().when(projection.getStoreId()).thenReturn(storeId);
		lenient().when(projection.getResizedKey320()).thenReturn(resized);
		lenient().when(projection.getAwsKey()).thenReturn(awsKey);
		return projection;
	}
}
