package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.abuse.entity.ModerationAppeal;
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.enums.ModerationAppealStatus;
import sweetgirl.croissant.abuse.enums.ModerationTargetType;
import sweetgirl.croissant.abuse.repository.ModerationAppealRepository;
import sweetgirl.croissant.admin.dto.response.AdminModerationAppealResponse;
import sweetgirl.croissant.member.entity.Member;

@ExtendWith(MockitoExtension.class)
class AdminModerationAppealQueryServiceTest {

	@Mock
	ModerationAppealRepository moderationAppealRepository;

	@InjectMocks
	AdminModerationAppealQueryService adminModerationAppealQueryService;

	@Test
	void getAppeals_filtersByStatus() {
		ModerationAppeal appeal = appeal(1L,
			moderationAction(55L, member(20L), ModerationTargetType.REVIEW, 123L, AbuseReportAction.HIDE_REVIEW));
		given(moderationAppealRepository.findByStatus(
			ModerationAppealStatus.PENDING,
			PageRequest.of(0, 20)))
			.willReturn(new PageImpl<>(List.of(appeal)));

		Page<AdminModerationAppealResponse> response = adminModerationAppealQueryService.getAppeals(
			"PENDING",
			0,
			20);

		assertThat(response.getContent()).hasSize(1);
		assertThat(response.getContent().get(0).appealId()).isEqualTo(1L);
		assertThat(response.getContent().get(0).status()).isEqualTo("PENDING");
	}

	private ModerationAppeal appeal(Long id, ModerationAction action) {
		ModerationAppeal appeal = ModerationAppeal.builder()
			.moderationAction(action)
			.member(action.getMember())
			.content("이의제기합니다.")
			.build();
		ReflectionTestUtils.setField(appeal, "id", id);
		ReflectionTestUtils.setField(appeal, "createdAt", LocalDateTime.of(2026, 6, 5, 10, 0));
		return appeal;
	}

	private ModerationAction moderationAction(
			Long id,
			Member member,
			ModerationTargetType targetType,
			Long targetId,
			AbuseReportAction action) {
		ModerationAction moderationAction = ModerationAction.builder()
			.targetType(targetType)
			.targetId(targetId)
			.member(member)
			.action(action)
			.reason("운영 정책 위반")
			.build();
		ReflectionTestUtils.setField(moderationAction, "id", id);
		return moderationAction;
	}

	private Member member(Long id) {
		Member member = Member.builder()
			.nickname("사용자")
			.build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}
}
