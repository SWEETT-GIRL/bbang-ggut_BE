package sweetgirl.croissant.letter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.letter.code.WelcomeLetterMessage;
import sweetgirl.croissant.letter.entity.LetterMessage;
import sweetgirl.croissant.letter.entity.LetterThread;
import sweetgirl.croissant.letter.enums.LetterCategory;
import sweetgirl.croissant.letter.enums.LetterSender;
import sweetgirl.croissant.letter.repository.LetterMessageRepository;
import sweetgirl.croissant.letter.repository.LetterThreadRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class WelcomeLetterServiceTest {

	@Mock
	private LetterThreadRepository letterThreadRepository;

	@Mock
	private LetterMessageRepository letterMessageRepository;

	@Mock
	private MemberRepository memberRepository;

	@InjectMocks
	private WelcomeLetterService welcomeLetterService;

	@Test
	@DisplayName("대화방이 없는 새 회원에게 인사 편지를 넣고 대화를 닫는다")
	void sendsWelcomeAndClosesThread() {
		Member member = member(1L);
		given(letterThreadRepository.findByMemberId(1L)).willReturn(Optional.empty());
		given(memberRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(member));
		given(letterThreadRepository.save(any(LetterThread.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		welcomeLetterService.sendWelcome(1L);

		ArgumentCaptor<LetterMessage> captor = ArgumentCaptor.forClass(LetterMessage.class);
		verify(letterMessageRepository).save(captor.capture());
		LetterMessage saved = captor.getValue();
		assertThat(saved.getSender()).isEqualTo(LetterSender.ADMIN);
		assertThat(saved.getBody()).isEqualTo(WelcomeLetterMessage.BODY);
		assertThat(saved.getCategory()).isEqualTo(LetterCategory.OUTREACH);
		// 닫아두지 않으면 앱이 진행 중인 대화로 보고 문의 유형을 묻지 않는다
		assertThat(saved.getThread().isResolved()).isTrue();
	}

	@Test
	@DisplayName("이미 대화방이 있으면 인사를 넣지 않는다 — 게스트가 회원으로 승격된 경우")
	void skipsWhenThreadAlreadyExists() {
		Member member = member(1L);
		LetterThread existing = LetterThread.create(member, LocalDateTime.of(2026, 9, 4, 10, 0));
		given(letterThreadRepository.findByMemberId(1L)).willReturn(Optional.of(existing));

		welcomeLetterService.sendWelcome(1L);

		verify(letterThreadRepository, never()).save(any());
		verify(letterMessageRepository, never()).save(any());
	}

	@Test
	@DisplayName("탈퇴했거나 없는 회원이면 아무 것도 하지 않는다")
	void skipsWhenMemberGone() {
		given(letterThreadRepository.findByMemberId(1L)).willReturn(Optional.empty());
		given(memberRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

		welcomeLetterService.sendWelcome(1L);

		verify(letterThreadRepository, never()).save(any());
		verify(letterMessageRepository, never()).save(any());
	}

	private Member member(Long id) {
		Member member = Member.builder().nickname("동글동글한 에그타르트").build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}
}
