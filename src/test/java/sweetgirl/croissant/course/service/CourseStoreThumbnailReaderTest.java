package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

@ExtendWith(MockitoExtension.class)
class CourseStoreThumbnailReaderTest {

	@Mock
	StoreImageFileRepository storeImageFileRepository;

	@InjectMocks
	CourseStoreThumbnailReader reader;

	@Test
	@DisplayName("read: 사진 없는 가게는 건너뛰고 방문 순서대로 최대 4장만 — 개수는 '사진 있는 가게 수'다")
	void read_skipsStoresWithoutPhoto_andCapsAtFour() {
		// 가게 1~6 중 2·5는 사진 없음
		List<CourseStore> courseStores = List.of(
			courseStore(1L), courseStore(2L), courseStore(3L), courseStore(4L), courseStore(5L), courseStore(6L));
		// 스텁 안에서 또 스텁(mock 생성)을 하면 Mockito가 미완성 스텁으로 본다 — 먼저 만들어 둔다
		List<StoreImageThumbnailProjection> thumbs = List.of(
			thumb(1L, "s1-320", "s1"),
			thumb(3L, null, "s3"), // 리사이즈 전이면 원본 키로
			thumb(4L, "s4-320", "s4"),
			thumb(6L, "s6-320", "s6"));
		given(storeImageFileRepository.findThumbnailsByStoreIdIn(anyList())).willReturn(thumbs);

		List<String> keys = reader.read(courseStores);

		assertThat(keys).containsExactly("s1-320", "s3", "s4-320", "s6-320");
	}

	@Test
	@DisplayName("readByCourse: 코스별 가게 id 로 갈라 담고, 사진 있는 가게가 없으면 빈 목록")
	void readByCourse_groupsPerCourse() {
		Map<Long, List<Long>> byCourse = Map.of(
			10L, List.of(1L, 2L),
			20L, List.of(2L));
		List<StoreImageThumbnailProjection> thumbs = List.of(thumb(1L, "s1-320", "s1"));
		given(storeImageFileRepository.findThumbnailsByStoreIdIn(anyList())).willReturn(thumbs);

		Map<Long, List<String>> result = reader.readByCourse(byCourse);

		assertThat(result.get(10L)).containsExactly("s1-320");
		assertThat(result.get(20L)).isEmpty();
	}

	private static CourseStore courseStore(Long storeId) {
		Store store = mock(Store.class);
		lenient().when(store.getId()).thenReturn(storeId);
		CourseStore courseStore = mock(CourseStore.class);
		lenient().when(courseStore.getStore()).thenReturn(store);
		return courseStore;
	}

	private static StoreImageThumbnailProjection thumb(Long storeId, String resized320, String awsKey) {
		StoreImageThumbnailProjection projection = mock(StoreImageThumbnailProjection.class);
		lenient().when(projection.getStoreId()).thenReturn(storeId);
		lenient().when(projection.getResizedKey320()).thenReturn(resized320);
		lenient().when(projection.getAwsKey()).thenReturn(awsKey);
		return projection;
	}
}
