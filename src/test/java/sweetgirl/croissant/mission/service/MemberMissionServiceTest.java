package sweetgirl.croissant.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.badge.enums.ConditionType;
import sweetgirl.croissant.badge.service.ConditionProgress;
import sweetgirl.croissant.badge.service.MemberConditionEvaluator;
import sweetgirl.croissant.badge.service.MemberConditionSnapshot;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.mission.entity.Mission;
import sweetgirl.croissant.mission.repository.MissionRepository;

@ExtendWith(MockitoExtension.class)
class MemberMissionServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	MissionRepository missionRepository;
	@Mock
	MemberConditionEvaluator conditionEvaluator;

	@InjectMocks
	MemberMissionService memberMissionService;

	@Test
	void getMissions_returnsServerCalculatedProgressInCatalogOrder() {
		Mission save = Mission.create(
			"starter-save", "찜하기", ConditionType.SAVED_STORE_COUNT, 1,
			"daedongbbangmap://search", "welcome", 1);
		Mission record = Mission.create(
			"record-5", "기록 다섯 개", ConditionType.RECORD_COUNT, 5,
			"daedongbbangmap://bbanglog/record", null, 2);
		MemberConditionSnapshot snapshot = new MemberConditionSnapshot(1, 0, 2, 0, 1);
		given(missionRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc())
			.willReturn(List.of(save, record));
		given(conditionEvaluator.snapshot(7L)).willReturn(snapshot);
		given(conditionEvaluator.evaluate(ConditionType.SAVED_STORE_COUNT, 1, snapshot))
			.willReturn(new ConditionProgress(1, 1));
		given(conditionEvaluator.evaluate(ConditionType.RECORD_COUNT, 5, snapshot))
			.willReturn(new ConditionProgress(2, 5));

		var result = memberMissionService.getMissions(7L);

		assertThat(result).extracting(response -> response.code()).containsExactly("starter-save", "record-5");
		assertThat(result.get(0).done()).isTrue();
		assertThat(result.get(1).progress().current()).isEqualTo(2);
		assertThat(result.get(1).progress().target()).isEqualTo(5);
	}
}
