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

import sweetgirl.croissant.badge.enums.ConditionType;
import sweetgirl.croissant.badge.repository.BadgeRepository;
import sweetgirl.croissant.mission.dto.request.AdminMissionCreateRequest;
import sweetgirl.croissant.mission.dto.request.AdminMissionUpdateRequest;
import sweetgirl.croissant.mission.entity.Mission;
import sweetgirl.croissant.mission.exception.MissionException;
import sweetgirl.croissant.mission.repository.MissionRepository;

@ExtendWith(MockitoExtension.class)
class AdminMissionServiceTest {

	@Mock
	MissionRepository missionRepository;
	@Mock
	BadgeRepository badgeRepository;

	@InjectMocks
	AdminMissionService adminMissionService;

	@Test
	void create_validatesBadgeAndPersistsMission() {
		AdminMissionCreateRequest request = new AdminMissionCreateRequest(
			"record-5", "기록 다섯 개", ConditionType.RECORD_COUNT, 5,
			"daedongbbangmap://bbanglog/record", "welcome", 4);
		given(badgeRepository.existsByCode("welcome")).willReturn(true);
		given(missionRepository.save(org.mockito.ArgumentMatchers.any(Mission.class)))
			.willAnswer(invocation -> {
				Mission mission = invocation.getArgument(0);
				ReflectionTestUtils.setField(mission, "id", 4L);
				return mission;
			});

		assertThat(adminMissionService.create(request)).isEqualTo(4L);
	}

	@Test
	void create_rejectsUnknownBadgeCode() {
		AdminMissionCreateRequest request = new AdminMissionCreateRequest(
			"record-5", "기록 다섯 개", ConditionType.RECORD_COUNT, 5,
			"daedongbbangmap://bbanglog/record", "missing", 4);

		assertThatThrownBy(() -> adminMissionService.create(request))
			.isInstanceOf(MissionException.class)
			.hasMessage("연결할 뱃지를 찾을 수 없습니다.");
	}

	@Test
	void update_blankBadgeCodeRemovesBadgeLink() {
		Mission mission = Mission.create(
			"record-5", "기록", ConditionType.RECORD_COUNT, 5,
			"daedongbbangmap://bbanglog/record", "welcome", 1);
		given(missionRepository.findById(1L)).willReturn(Optional.of(mission));

		adminMissionService.update(1L, new AdminMissionUpdateRequest(
			null, null, null, null, " ", null, null));

		assertThat(mission.getBadgeCode()).isNull();
	}

	@Test
	void update_rejectsThresholdOnStarterAll() {
		Mission mission = Mission.create(
			"starter-all", "시작 완료", ConditionType.STARTER_ALL, null,
			"daedongbbangmap://bbanglog", null, 1);
		given(missionRepository.findById(1L)).willReturn(Optional.of(mission));

		assertThatThrownBy(() -> adminMissionService.update(1L, new AdminMissionUpdateRequest(
			null, null, 1, null, null, null, null)))
			.isInstanceOf(MissionException.class)
			.hasMessage("미션 조건과 threshold가 올바르지 않습니다.");
	}
}
