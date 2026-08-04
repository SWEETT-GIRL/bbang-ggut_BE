package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.badge.dto.request.AdminBadgeCreateRequest;
import sweetgirl.croissant.badge.dto.request.AdminBadgeUpdateRequest;
import sweetgirl.croissant.badge.entity.Badge;
import sweetgirl.croissant.badge.enums.ConditionType;
import sweetgirl.croissant.badge.exception.BadgeException;
import sweetgirl.croissant.badge.repository.BadgeRepository;

@ExtendWith(MockitoExtension.class)
class AdminBadgeServiceTest {

	@Mock
	BadgeRepository badgeRepository;

	@InjectMocks
	AdminBadgeService adminBadgeService;

	@Test
	void create_persistsDataDrivenCondition() {
		AdminBadgeCreateRequest request = new AdminBadgeCreateRequest(
			"record-10", "badges/record-10.png", "기록 열 개", "빵 기록 10개", ConditionType.RECORD_COUNT, 10, 4);
		given(badgeRepository.save(org.mockito.ArgumentMatchers.any(Badge.class)))
			.willAnswer(invocation -> {
				Badge badge = invocation.getArgument(0);
				ReflectionTestUtils.setField(badge, "id", 4L);
				return badge;
			});

		Long badgeId = adminBadgeService.create(request);

		assertThat(badgeId).isEqualTo(4L);
	}

	@Test
	void create_rejectsThresholdOnStarterAll() {
		AdminBadgeCreateRequest request = new AdminBadgeCreateRequest(
			"welcome-2", null, "웰컴", "시작하기", ConditionType.STARTER_ALL, 1, 1);

		assertThatThrownBy(() -> adminBadgeService.create(request))
			.isInstanceOf(BadgeException.class)
			.hasMessage("뱃지 조건과 threshold가 올바르지 않습니다.");
	}

	@Test
	void update_switchingToStarterAllClearsThreshold() {
		Badge badge = Badge.create("record-10", null, "기록", "기록하기", ConditionType.RECORD_COUNT, 10, 1);
		given(badgeRepository.findById(1L)).willReturn(Optional.of(badge));

		adminBadgeService.update(1L, new AdminBadgeUpdateRequest(
			null, null, null, ConditionType.STARTER_ALL, null, null, null));

		assertThat(badge.getConditionType()).isEqualTo(ConditionType.STARTER_ALL);
		assertThat(badge.getThreshold()).isNull();
	}

	@Test
	void update_rejectsThresholdOnStarterAll() {
		Badge badge = Badge.create("welcome", null, "웰컴", "시작하기", ConditionType.STARTER_ALL, null, 1);
		given(badgeRepository.findById(1L)).willReturn(Optional.of(badge));

		assertThatThrownBy(() -> adminBadgeService.update(1L, new AdminBadgeUpdateRequest(
			null, null, null, null, 1, null, null)))
			.isInstanceOf(BadgeException.class)
			.hasMessage("뱃지 조건과 threshold가 올바르지 않습니다.");
	}

	@Test
	void deactivate_preservesCatalogRow() {
		Badge badge = Badge.create("regular", null, "단골", "세 번 방문", ConditionType.STORE_REVISIT, 3, 1);
		given(badgeRepository.findById(1L)).willReturn(Optional.of(badge));

		adminBadgeService.deactivate(1L);

		assertThat(badge.isActive()).isFalse();
	}
}
