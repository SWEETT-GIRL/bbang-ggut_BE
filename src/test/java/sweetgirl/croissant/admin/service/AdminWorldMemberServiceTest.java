package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.world.dto.request.AdminWorldMemberGrantRequest;
import sweetgirl.croissant.world.entity.MemberWakeTicket;
import sweetgirl.croissant.world.entity.MemberWorld;
import sweetgirl.croissant.world.entity.WorldFixtures;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.repository.MemberPlanetArrivalRepository;
import sweetgirl.croissant.world.repository.MemberWakeTicketRepository;
import sweetgirl.croissant.world.repository.MemberWorldRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.service.WorldCatalogService;

@ExtendWith(MockitoExtension.class)
class AdminWorldMemberServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private MemberWorldRepository memberWorldRepository;
	@Mock
	private MemberCharacterRepository memberCharacterRepository;
	@Mock
	private MemberPlanetArrivalRepository memberPlanetArrivalRepository;
	@Mock
	private MemberWakeTicketRepository memberWakeTicketRepository;
	@Mock
	private WorldCharacterRepository worldCharacterRepository;
	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private WorldCatalogService worldCatalogService;

	@InjectMocks
	private AdminWorldMemberService adminWorldMemberService;

	private MemberWorld givenStartedMember() {
		MemberWorld memberWorld = WorldFixtures.memberWorld(MEMBER_ID, "dog", 12);
		given(memberWorldRepository.findById(MEMBER_ID)).willReturn(Optional.of(memberWorld));
		return memberWorld;
	}

	@Test
	@DisplayName("캐릭터를 깨어난 채로 지급하고 획득 경로를 ADMIN 으로 남긴다")
	void grantsCharacter() {
		givenStartedMember();
		given(worldCharacterRepository.existsById("quoka")).willReturn(true);

		adminWorldMemberService.grant(MEMBER_ID,
			new AdminWorldMemberGrantRequest("quoka", null, null));

		then(memberCharacterRepository).should().grantIfAbsent(MEMBER_ID, "quoka", "ADMIN");
		then(memberCharacterRepository).should().awakeIfSleeping(MEMBER_ID, "quoka", "ADMIN");
	}

	@Test
	@DisplayName("없는 캐릭터는 지급하지 않는다")
	void rejectsUnknownCharacter() {
		givenStartedMember();
		given(worldCharacterRepository.existsById("pluto")).willReturn(false);

		assertThatThrownBy(() -> adminWorldMemberService.grant(MEMBER_ID,
			new AdminWorldMemberGrantRequest("pluto", null, null)))
			.isInstanceOf(WorldException.class);
		then(memberCharacterRepository).should(never()).grantIfAbsent(any(), any(), any());
	}

	@Test
	@DisplayName("진행도를 고쳐 준다")
	void adjustsProgress() {
		MemberWorld memberWorld = givenStartedMember();

		adminWorldMemberService.grant(MEMBER_ID, new AdminWorldMemberGrantRequest(null, 40, null));

		assertThat(memberWorld.getMaxRecordCount()).isEqualTo(40);
	}

	@Test
	@DisplayName("티켓을 요청한 수만큼 발급한다")
	void issuesTickets() {
		givenStartedMember();

		adminWorldMemberService.grant(MEMBER_ID, new AdminWorldMemberGrantRequest(null, null, 3));

		ArgumentCaptor<List<MemberWakeTicket>> saved = ArgumentCaptor.captor();
		then(memberWakeTicketRepository).should().saveAll(saved.capture());
		assertThat(saved.getValue()).hasSize(3)
			.allSatisfy(ticket -> assertThat(ticket.getMemberId()).isEqualTo(MEMBER_ID));
	}

	@Test
	@DisplayName("비워 보낸 칸은 건드리지 않는다")
	void leavesOmittedFieldsAlone() {
		MemberWorld memberWorld = givenStartedMember();

		adminWorldMemberService.grant(MEMBER_ID, new AdminWorldMemberGrantRequest(null, null, null));

		assertThat(memberWorld.getMaxRecordCount()).isEqualTo(12);
		then(memberCharacterRepository).should(never()).grantIfAbsent(any(), any(), any());
		then(memberWakeTicketRepository).should(never()).saveAll(any());
	}

	@Test
	@DisplayName("여정을 시작하지 않은 회원이면 예외다")
	void rejectsUnstartedMember() {
		given(memberWorldRepository.findById(MEMBER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> adminWorldMemberService.grant(MEMBER_ID,
			new AdminWorldMemberGrantRequest("quoka", null, null)))
			.isInstanceOf(WorldException.class);
	}
}
