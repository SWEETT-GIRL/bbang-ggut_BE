package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.admin.dto.response.AdminBreadTagReclassifyResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.breadlog.service.BreadCategoryClassifier;

@ExtendWith(MockitoExtension.class)
class AdminBreadTagCommandServiceTest {

	@Mock
	private BreadRecordTagRepository breadRecordTagRepository;
	@Mock
	private BreadCategoryClassifier breadCategoryClassifier;

	@InjectMocks
	private AdminBreadTagCommandService service;

	private static BreadRecordTag tag(String name, String category) {
		return BreadRecordTag.builder().name(name).breadCategory(category).positionX(0.5).positionY(0.5).build();
	}

	@Test
	@DisplayName("현재 규칙으로 다시 분류해 바뀐 태그만 갱신하고, 어디서 어디로 옮겼는지 센다")
	void reclassifyAll_updatesChangedOnly() {
		BreadRecordTag sio = tag("시오빵", "빵");
		BreadRecordTag castella = tag("사각 카스테라(초코)", "기타");
		BreadRecordTag salt = tag("쪽파 소금빵", "소금빵");
		given(breadRecordTagRepository.findAll()).willReturn(List.of(sio, castella, salt));
		given(breadCategoryClassifier.classify("시오빵")).willReturn("소금빵");
		given(breadCategoryClassifier.classify("사각 카스테라(초코)")).willReturn("카스테라");
		given(breadCategoryClassifier.classify("쪽파 소금빵")).willReturn("소금빵");

		AdminBreadTagReclassifyResponse result = service.reclassifyAll();

		assertThat(result.total()).isEqualTo(3);
		assertThat(result.changed()).isEqualTo(2);
		assertThat(result.moves()).containsExactlyInAnyOrderEntriesOf(Map.of(
			"빵 → 소금빵", 1L,
			"기타 → 카스테라", 1L));
		assertThat(sio.getBreadCategory()).isEqualTo("소금빵");
		assertThat(castella.getBreadCategory()).isEqualTo("카스테라");
		assertThat(salt.getBreadCategory()).isEqualTo("소금빵");
	}

	@Test
	@DisplayName("태그가 없으면 0건")
	void reclassifyAll_empty() {
		given(breadRecordTagRepository.findAll()).willReturn(List.of());

		AdminBreadTagReclassifyResponse result = service.reclassifyAll();

		assertThat(result.total()).isZero();
		assertThat(result.changed()).isZero();
		assertThat(result.moves()).isEmpty();
	}
}
