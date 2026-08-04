package sweetgirl.croissant.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.badge.enums.ConditionType;
import sweetgirl.croissant.badge.service.ConditionProgress;
import sweetgirl.croissant.badge.service.MemberConditionEvaluator;
import sweetgirl.croissant.badge.service.MemberConditionSnapshot;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.mission.entity.MemberMission;
import sweetgirl.croissant.mission.entity.Mission;
import sweetgirl.croissant.mission.repository.MemberMissionRepository;
import sweetgirl.croissant.mission.repository.MissionRepository;

@ExtendWith(MockitoExtension.class)
class MemberMissionServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	MissionRepository missionRepository;
	@Mock
	MemberMissionRepository memberMissionRepository;
	@Mock
	MemberConditionEvaluator conditionEvaluator;

	@InjectMocks
	MemberMissionService memberMissionService;

	@Test
	void getMissions_returnsServerCalculatedProgressInCatalogOrder() {
		Mission save = mission(1L, "starter-save", "찜하기", ConditionType.SAVED_STORE_COUNT, 1);
		Mission record = mission(2L, "record-5", "기록 다섯 개", ConditionType.RECORD_COUNT, 5);
		MemberConditionSnapshot snapshot = new MemberConditionSnapshot(1, 0, 2, 0, 1);
		given(missionRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc())
			.willReturn(List.of(save, record));
		given(memberMissionRepository.findAllByMemberId(7L)).willReturn(List.of());
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

	@Test
	@DisplayName("조건을 채우면 완료로 기록해 둔다 — 다음 조회부터는 다시 재지 않는다")
	void getMissions_recordsCompletionWhenSatisfied() {
		Mission save = mission(1L, "starter-save", "찜하기", ConditionType.SAVED_STORE_COUNT, 1);
		MemberConditionSnapshot snapshot = new MemberConditionSnapshot(1, 0, 0, 0, 1);
		given(missionRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc()).willReturn(List.of(save));
		MemberMission saved = memberMission(save);
		given(memberMissionRepository.findAllByMemberId(7L))
			.willReturn(List.of(), List.of(saved));
		given(conditionEvaluator.snapshot(7L)).willReturn(snapshot);
		given(conditionEvaluator.evaluate(ConditionType.SAVED_STORE_COUNT, 1, snapshot))
			.willReturn(new ConditionProgress(1, 1));

		var result = memberMissionService.getMissions(7L);

		then(memberMissionRepository).should().completeIfAbsent(7L, 1L);
		assertThat(result.get(0).done()).isTrue();
	}

	@Test
	@DisplayName("끝낸 미션은 근거를 지워도 완료로 남는다 — 해낸 일이 없던 일이 되지 않는다")
	void getMissions_keepsCompletionAfterEvidenceIsDeleted() {
		Mission record = mission(2L, "starter-record", "첫 빵 기록", ConditionType.RECORD_COUNT, 1);
		given(missionRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc()).willReturn(List.of(record));
		// 이미 끝냈다고 기록돼 있고, 지금은 기록이 0개다(사용자가 지웠다).
		MemberMission done = memberMission(record);
		given(memberMissionRepository.findAllByMemberId(7L)).willReturn(List.of(done));
		MemberConditionSnapshot empty = new MemberConditionSnapshot(0, 0, 0, 0, 0);
		given(conditionEvaluator.snapshot(7L)).willReturn(empty);
		given(conditionEvaluator.evaluate(ConditionType.RECORD_COUNT, 1, empty))
			.willReturn(new ConditionProgress(0, 1));

		var result = memberMissionService.getMissions(7L);

		assertThat(result.get(0).done()).isTrue();
		// 진행률도 되감기지 않는다 — 다 찬 칸이 도로 비면 완료가 취소된 것으로 읽힌다.
		assertThat(result.get(0).progress().current()).isEqualTo(1);
		then(memberMissionRepository).should(never()).completeIfAbsent(any(), any());
	}

	private Mission mission(Long id, String code, String title, ConditionType type, Integer threshold) {
		Mission mission = Mission.create(
			code, title, type, threshold, "daedongbbangmap://search", null, 1);
		ReflectionTestUtils.setField(mission, "id", id);
		return mission;
	}

	private MemberMission memberMission(Mission mission) {
		Member member = org.mockito.Mockito.mock(Member.class);
		org.mockito.Mockito.lenient().when(member.getId()).thenReturn(7L);
		return MemberMission.completed(member, mission, LocalDateTime.of(2026, 8, 4, 12, 0));
	}
}
