package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.dto.request.BlockCreateRequest;
import sweetgirl.croissant.member.dto.response.BlockResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.MemberBlock;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class MemberBlockCommandServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	MemberRepository memberRepository;
	@Mock
	MemberBlockRepository memberBlockRepository;

	@InjectMocks
	MemberBlockCommandService memberBlockCommandService;

	@Test
	void blockMember_validRequest_savesBlock() {
		Member blocker = member(1L, "차단자");
		Member blockedMember = member(2L, "대상자");
		given(memberReader.getMember(1L)).willReturn(blocker);
		given(memberRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.of(blockedMember));
		given(memberBlockRepository.findByBlockerIdAndBlockedMemberId(1L, 2L)).willReturn(Optional.empty());
		given(memberBlockRepository.save(any(MemberBlock.class))).willAnswer(invocation -> {
			MemberBlock memberBlock = invocation.getArgument(0);
			ReflectionTestUtils.setField(memberBlock, "id", 10L);
			ReflectionTestUtils.setField(memberBlock, "createdAt", LocalDateTime.of(2026, 6, 5, 10, 0));
			return memberBlock;
		});

		BlockResponse response = memberBlockCommandService.blockMember(1L, new BlockCreateRequest(2L));

		assertThat(response.memberId()).isEqualTo(2L);
		assertThat(response.nickname()).isEqualTo("대상자");
		assertThat(response.blockedAt()).isEqualTo(LocalDateTime.of(2026, 6, 5, 10, 0));

		ArgumentCaptor<MemberBlock> captor = ArgumentCaptor.forClass(MemberBlock.class);
		verify(memberBlockRepository).save(captor.capture());
		assertThat(captor.getValue().getBlocker()).isEqualTo(blocker);
		assertThat(captor.getValue().getBlockedMember()).isEqualTo(blockedMember);
	}

	@Test
	void blockMember_duplicateRequest_returnsExistingBlock() {
		Member blocker = member(1L, "차단자");
		Member blockedMember = member(2L, "대상자");
		MemberBlock existingBlock = memberBlock(10L, blocker, blockedMember, LocalDateTime.of(2026, 6, 5, 10, 0));
		given(memberReader.getMember(1L)).willReturn(blocker);
		given(memberRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.of(blockedMember));
		given(memberBlockRepository.findByBlockerIdAndBlockedMemberId(1L, 2L))
			.willReturn(Optional.of(existingBlock));

		BlockResponse response = memberBlockCommandService.blockMember(1L, new BlockCreateRequest(2L));

		assertThat(response.memberId()).isEqualTo(2L);
		assertThat(response.nickname()).isEqualTo("대상자");
		verify(memberBlockRepository, never()).save(any());
	}

	@Test
	void blockMember_selfTarget_throwsBadRequest() {
		Member blocker = member(1L, "차단자");
		given(memberReader.getMember(1L)).willReturn(blocker);

		assertThatThrownBy(() -> memberBlockCommandService.blockMember(1L, new BlockCreateRequest(1L)))
			.isInstanceOf(MemberException.class)
			.hasMessageContaining(MemberErrorCode.MEMBER_BLOCK_SELF_NOT_ALLOWED.getMessage());

		verify(memberRepository, never()).findByIdAndDeletedAtIsNull(any());
		verify(memberBlockRepository, never()).save(any());
	}

	@Test
	void blockMember_missingTarget_throwsNotFound() {
		Member blocker = member(1L, "차단자");
		given(memberReader.getMember(1L)).willReturn(blocker);
		given(memberRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> memberBlockCommandService.blockMember(1L, new BlockCreateRequest(2L)))
			.isInstanceOf(MemberException.class)
			.hasMessageContaining(MemberErrorCode.MEMBER_BLOCK_TARGET_NOT_FOUND.getMessage());

		verify(memberBlockRepository, never()).save(any());
	}

	@Test
	void unblockMember_deletesBlockByPairOnly() {
		Member blocker = member(1L, "차단자");
		given(memberReader.getMember(1L)).willReturn(blocker);

		memberBlockCommandService.unblockMember(1L, 2L);

		verify(memberBlockRepository).deleteByBlockerIdAndBlockedMemberId(1L, 2L);
		verify(memberRepository, never()).findByIdAndDeletedAtIsNull(any());
	}

	private Member member(Long id, String nickname) {
		Member member = Member.builder()
			.nickname(nickname)
			.build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private MemberBlock memberBlock(Long id, Member blocker, Member blockedMember, LocalDateTime createdAt) {
		MemberBlock memberBlock = MemberBlock.builder()
			.blocker(blocker)
			.blockedMember(blockedMember)
			.build();
		ReflectionTestUtils.setField(memberBlock, "id", id);
		ReflectionTestUtils.setField(memberBlock, "createdAt", createdAt);
		return memberBlock;
	}
}
