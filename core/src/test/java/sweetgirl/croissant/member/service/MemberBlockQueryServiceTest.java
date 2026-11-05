package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.member.dto.response.BlockListResponse;
import sweetgirl.croissant.member.dto.response.BlockResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.MemberBlock;
import sweetgirl.croissant.member.repository.MemberBlockRepository;

@ExtendWith(MockitoExtension.class)
class MemberBlockQueryServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	MemberBlockRepository memberBlockRepository;
	@Mock
	sweetgirl.croissant.profile.service.CharacterFaceReader characterFaceReader;

	@InjectMocks
	MemberBlockQueryService memberBlockQueryService;

	@Test
	void getBlocks_pagesWithSliceAndHasNext() {
		Member blocker = member(1L, "차단자");
		Member blockedMember1 = member(2L, "대상자1");
		given(memberReader.getMember(1L)).willReturn(blocker);
		given(memberBlockRepository.findAllByBlockerIdOrderByCreatedAtDescIdDesc(eq(1L), any(Pageable.class)))
			.willReturn(new SliceImpl<>(List.of(
				memberBlock(10L, blocker, blockedMember1, LocalDateTime.of(2026, 6, 5, 10, 0))),
				PageRequest.of(0, 1), true));

		BlockListResponse response = memberBlockQueryService.getBlocks(1L, 0, 1);

		assertThat(response.content()).hasSize(1);
		assertThat(response.content().get(0).memberId()).isEqualTo(2L);
		assertThat(response.hasNext()).isTrue();
		verify(memberBlockRepository).findAllByBlockerIdOrderByCreatedAtDescIdDesc(
			eq(1L),
			org.mockito.ArgumentMatchers.argThat(pageable -> pageable.getPageNumber() == 0
				&& pageable.getPageSize() == 1));
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
