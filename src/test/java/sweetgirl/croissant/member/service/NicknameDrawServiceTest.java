package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import static org.mockito.BDDMockito.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.member.code.NicknameErrorCode;
import sweetgirl.croissant.member.dto.response.MemberProfileResponse;
import sweetgirl.croissant.member.dto.response.NicknameDrawResponse;
import sweetgirl.croissant.member.dto.response.NicknameDrawStatusResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.NicknameException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.util.NicknameGenerator;

@ExtendWith(MockitoExtension.class)
class NicknameDrawServiceTest {

	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	@Mock
	private MemberReader memberReader;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private NicknameGenerator nicknameGenerator;

	@InjectMocks
	private NicknameDrawService nicknameDrawService;

	private Member member;

	@BeforeEach
	void setUp() {
		member = Member.builder().nickname("달콤한 소금빵").build();
		ReflectionTestUtils.setField(member, "id", 1L);
		// drawWithAd 는 탈퇴 회원도 조용히 넘기려고 memberReader 를 거치지 않는다
		lenient().when(memberReader.getMember(1L)).thenReturn(member);
	}

	@Test
	void draw_setsPendingAndKeepsNickname_whenNeverDrawn() {
		given(nicknameGenerator.generate()).willReturn("바삭한 츄러스");

		NicknameDrawResponse response = nicknameDrawService.draw(1L);

		assertThat(response.currentNickname()).isEqualTo("달콤한 소금빵");
		assertThat(response.candidate()).isEqualTo("바삭한 츄러스");
		assertThat(member.getNickname()).isEqualTo("달콤한 소금빵");
		assertThat(member.getPendingNickname()).isEqualTo("바삭한 츄러스");
		assertThat(member.getNicknameDrawnAt()).isNotNull();
	}

	@Test
	void draw_succeeds_whenLastDrawWasBeforeThisWeek() {
		ReflectionTestUtils.setField(member, "nicknameDrawnAt", thisMonday().minusDays(1).atTime(23, 59));
		given(nicknameGenerator.generate()).willReturn("바삭한 츄러스");

		assertThat(nicknameDrawService.draw(1L).candidate()).isEqualTo("바삭한 츄러스");
	}

	@Test
	void draw_throws429FreeUsed_whenDrawnEarlierThisWeek() {
		assumeFalse(LocalDate.now(ZONE).getDayOfWeek() == DayOfWeek.MONDAY, "월요일엔 '이번 주 어제'가 없다");
		ReflectionTestUtils.setField(member, "nicknameDrawnAt", thisMonday().atStartOfDay());

		assertThatThrownBy(() -> nicknameDrawService.draw(1L))
			.isInstanceOf(NicknameException.class)
			.hasFieldOrPropertyWithValue("errorCode", NicknameErrorCode.NICKNAME_FREE_DRAW_USED);
		then(nicknameGenerator).shouldHaveNoInteractions();
	}

	@Test
	void usedFreeNicknameDraw_countsOnlyDrawsFromMondayOn() {
		LocalDate monday = LocalDate.of(2026, 9, 21);
		ReflectionTestUtils.setField(member, "nicknameDrawnAt", LocalDateTime.of(2026, 9, 20, 23, 59));
		assertThat(member.usedFreeNicknameDraw(monday)).isFalse();

		ReflectionTestUtils.setField(member, "nicknameDrawnAt", LocalDateTime.of(2026, 9, 21, 0, 0));
		assertThat(member.usedFreeNicknameDraw(monday)).isTrue();
	}

	@Test
	void draw_throws429_whenAlreadyDrawnToday() {
		ReflectionTestUtils.setField(member, "nicknameDrawnAt", LocalDate.now(ZONE).atStartOfDay());

		assertThatThrownBy(() -> nicknameDrawService.draw(1L))
			.isInstanceOf(NicknameException.class)
			.hasFieldOrPropertyWithValue("errorCode", NicknameErrorCode.NICKNAME_DRAW_LIMIT);
		then(nicknameGenerator).shouldHaveNoInteractions();
	}

	@Test
	void draw_throws429_afterDiscardOnSameDay() {
		given(nicknameGenerator.generate()).willReturn("바삭한 츄러스");
		nicknameDrawService.draw(1L);
		nicknameDrawService.discard(1L);

		assertThatThrownBy(() -> nicknameDrawService.draw(1L))
			.isInstanceOf(NicknameException.class)
			.hasFieldOrPropertyWithValue("errorCode", NicknameErrorCode.NICKNAME_DRAW_LIMIT);
	}

	@Test
	void accept_changesNicknameAndClearsPending() {
		member.drawNickname("바삭한 츄러스", LocalDateTime.now(ZONE));
		given(memberRepository.existsByNicknameAndDeletedAtIsNull("바삭한 츄러스")).willReturn(false);

		MemberProfileResponse response = nicknameDrawService.accept(1L);

		assertThat(response.nickname()).isEqualTo("바삭한 츄러스");
		assertThat(member.getNickname()).isEqualTo("바삭한 츄러스");
		assertThat(member.getPendingNickname()).isNull();
	}

	@Test
	void accept_throws400_whenNoPending() {
		assertThatThrownBy(() -> nicknameDrawService.accept(1L))
			.isInstanceOf(NicknameException.class)
			.hasFieldOrPropertyWithValue("errorCode", NicknameErrorCode.NICKNAME_NO_PENDING);
	}

	@Test
	void accept_throws409AndKeepsNickname_whenSomeoneElseTookIt() {
		member.drawNickname("바삭한 츄러스", LocalDateTime.now(ZONE));
		given(memberRepository.existsByNicknameAndDeletedAtIsNull("바삭한 츄러스")).willReturn(true);

		assertThatThrownBy(() -> nicknameDrawService.accept(1L))
			.isInstanceOf(NicknameException.class)
			.hasFieldOrPropertyWithValue("errorCode", NicknameErrorCode.NICKNAME_TAKEN);
		assertThat(member.getNickname()).isEqualTo("달콤한 소금빵");
	}

	@Test
	void discard_keepsNicknameAndClearsPending() {
		member.drawNickname("바삭한 츄러스", LocalDateTime.now(ZONE));

		MemberProfileResponse response = nicknameDrawService.discard(1L);

		assertThat(response.nickname()).isEqualTo("달콤한 소금빵");
		assertThat(member.getPendingNickname()).isNull();
	}

	@Test
	void getStatus_canDraw_whenNeverDrawn() {
		NicknameDrawStatusResponse status = nicknameDrawService.getStatus(1L);

		assertThat(status.canDraw()).isTrue();
		assertThat(status.pendingNickname()).isNull();
		assertThat(status.nextDrawAt()).isNull();
	}

	@Test
	void getStatus_returnsPendingAndTomorrowMidnight_whenDrawnToday() {
		member.drawNickname("바삭한 츄러스", LocalDateTime.now(ZONE));

		NicknameDrawStatusResponse status = nicknameDrawService.getStatus(1L);

		assertThat(status.canDraw()).isFalse();
		assertThat(status.canDrawWithAd()).isFalse();
		assertThat(status.pendingNickname()).isEqualTo("바삭한 츄러스");
		assertThat(status.nextDrawAt()).isEqualTo(LocalDate.now(ZONE).plusDays(1).atStartOfDay());
		assertThat(status.nextFreeDrawAt()).isEqualTo(thisMonday().plusWeeks(1).atStartOfDay());
	}

	@Test
	void getStatus_canDrawWithAd_whenFreeUsedEarlierThisWeek() {
		assumeFalse(LocalDate.now(ZONE).getDayOfWeek() == DayOfWeek.MONDAY, "월요일엔 '이번 주 어제'가 없다");
		ReflectionTestUtils.setField(member, "nicknameDrawnAt", thisMonday().atStartOfDay());

		NicknameDrawStatusResponse status = nicknameDrawService.getStatus(1L);

		assertThat(status.canDraw()).isFalse();
		assertThat(status.canDrawWithAd()).isTrue();
		assertThat(status.nextDrawAt()).isNull();
		assertThat(status.nextFreeDrawAt()).isEqualTo(thisMonday().plusWeeks(1).atStartOfDay());
	}

	@Test
	void drawWithAd_drawsOnlyAfterFreeUsedAndNotToday() {
		assumeFalse(LocalDate.now(ZONE).getDayOfWeek() == DayOfWeek.MONDAY, "월요일엔 '이번 주 어제'가 없다");
		ReflectionTestUtils.setField(member, "nicknameDrawnAt", thisMonday().atStartOfDay());
		given(memberRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(member));
		given(nicknameGenerator.generate()).willReturn("바삭한 츄러스");
		given(memberRepository.drawNicknameWithAdIfAllowed(eq(1L), eq("바삭한 츄러스"), any(),
			eq(thisMonday().atStartOfDay()), eq(LocalDate.now(ZONE).atStartOfDay()))).willReturn(1);

		assertThat(nicknameDrawService.drawWithAd(1L)).isTrue();
	}

	@Test
	void drawWithAd_refuses_whenFreeDrawStillLeft() {
		ReflectionTestUtils.setField(member, "nicknameDrawnAt", thisMonday().minusDays(1).atStartOfDay());
		given(memberRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(member));

		assertThat(nicknameDrawService.drawWithAd(1L)).isFalse();
		then(nicknameGenerator).shouldHaveNoInteractions();
	}

	@Test
	void drawWithAd_refuses_whenAlreadyDrawnToday() {
		member.drawNickname("바삭한 츄러스", LocalDateTime.now(ZONE));
		given(memberRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(member));

		assertThat(nicknameDrawService.drawWithAd(1L)).isFalse();
		then(nicknameGenerator).shouldHaveNoInteractions();
	}

	private static LocalDate thisMonday() {
		return LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}
}
