package sweetgirl.croissant.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.service.BreadRecordVisibilityPolicy;
import sweetgirl.croissant.comment.dto.response.CommentResponse;
import sweetgirl.croissant.comment.dto.response.MentionCandidateResponse;
import sweetgirl.croissant.comment.entity.BreadRecordComment;
import sweetgirl.croissant.comment.entity.CommentMention;
import sweetgirl.croissant.comment.repository.BreadRecordCommentRepository;
import sweetgirl.croissant.comment.repository.CommentMentionRepository;
import sweetgirl.croissant.follow.entity.MemberFollow;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.profile.dto.response.CharacterFace;
import sweetgirl.croissant.profile.service.CharacterFaceReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommentQueryService")
class CommentQueryServiceTest {

	private static final Long AUTHOR = 1L;
	private static final Long ME = 2L;
	private static final Long RECORD = 10L;

	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private BreadRecordCommentRepository commentRepository;
	@Mock
	private CommentMentionRepository mentionRepository;
	@Mock
	private BreadRecordVisibilityPolicy visibilityPolicy;
	@Mock
	private CharacterFaceReader characterFaceReader;
	@Mock
	private MemberFollowRepository memberFollowRepository;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private MemberReader memberReader;

	@Mock
	private sweetgirl.croissant.abuse.service.ReportedTargetReader reportedTargetReader;

	@InjectMocks
	private CommentQueryService queryService;

	private Member member(Long id, String nickname) {
		Member member = Member.builder().nickname(nickname).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private BreadRecord record() {
		BreadRecord record = BreadRecord.builder().member(member(AUTHOR, "작성자")).storeName("밀도").isPublic(true).build();
		ReflectionTestUtils.setField(record, "id", RECORD);
		return record;
	}

	private BreadRecordComment comment(Long id, Member writer, String content) {
		BreadRecordComment comment = BreadRecordComment.of(record(), writer, content);
		ReflectionTestUtils.setField(comment, "id", id);
		return comment;
	}

	@Test
	@DisplayName("댓글 줄마다 얼굴 · 멘션 · isAuthor · isMine 을 채우고, 탈퇴한 사람은 작성자를 가린다")
	void comments() {
		BreadRecord record = record();
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD)).willReturn(Optional.of(record));
		Member withdrawn = member(9L, "탈퇴한 회원");
		withdrawn.softDelete();
		BreadRecordComment byAuthor = comment(100L, member(AUTHOR, "작성자"), "제 빵로그예요");
		BreadRecordComment mine = comment(101L, member(ME, "나"), "@작성자 맛있어요");
		BreadRecordComment gone = comment(102L, withdrawn, "옛 댓글");
		given(commentRepository.findVisibleByRecordId(eq(RECORD), eq(ME), any()))
			.willReturn(new SliceImpl<>(List.of(byAuthor, mine, gone), Pageable.ofSize(20), false));
		CommentMention mention = CommentMention.of(mine, member(AUTHOR, "작성자"), 0, 4);
		given(mentionRepository.findAllByIdCommentIdIn(List.of(100L, 101L, 102L))).willReturn(List.of(mention));
		given(characterFaceReader.facesOf(any()))
			.willReturn(Map.of(ME, new CharacterFace("dog", "world/dog/front.png")));

		SliceResponse<CommentResponse> result = queryService.getComments(ME, RECORD, 0, 20);

		then(visibilityPolicy).should().validateCanView(ME, record);
		assertThat(result.content()).extracting(CommentResponse::isAuthor).containsExactly(true, false, false);
		assertThat(result.content()).extracting(CommentResponse::isMine).containsExactly(false, true, false);
		assertThat(result.content().get(1).characterCode()).isEqualTo("dog");
		assertThat(result.content().get(1).mentions())
			.containsExactly(new CommentResponse.MentionResponse(AUTHOR, 0, 4));
		CommentResponse withdrawnRow = result.content().get(2);
		assertThat(withdrawnRow.memberId()).isNull();
		assertThat(withdrawnRow.nickname()).isNull();
		assertThat(withdrawnRow.content()).isEqualTo("옛 댓글");
	}

	@Test
	@DisplayName("멘션 후보는 팔로잉 → 팔로워 → 댓글 단 사람 순으로 관계를 붙이고 앞글자로 거른다. 나는 뺀다")
	void mentionCandidates() {
		BreadRecord record = record();
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD)).willReturn(Optional.of(record));
		Member following = member(3L, "빵순이");
		Member follower = member(4L, "빵돌이");
		given(memberFollowRepository.findFollowings(eq(ME), eq(ME), isNull(), any()))
			.willReturn(new SliceImpl<>(List.of(MemberFollow.of(member(ME, "나"), following))));
		given(memberFollowRepository.findFollowers(eq(ME), eq(ME), isNull(), any()))
			.willReturn(new SliceImpl<>(List.of(MemberFollow.of(follower, member(ME, "나")),
				MemberFollow.of(following, member(ME, "나")))));
		given(commentRepository.findCommenterIds(RECORD)).willReturn(List.of(ME, AUTHOR));
		given(memberRepository.findSocialByIdIn(any())).willReturn(List.of(following, follower, member(AUTHOR, "작성자")));
		given(characterFaceReader.facesOf(any())).willReturn(Map.of());

		List<MentionCandidateResponse> result = queryService.mentionCandidates(ME, RECORD, "빵");

		assertThat(result).extracting(MentionCandidateResponse::memberId).containsExactly(4L, 3L);
		assertThat(result).extracting(MentionCandidateResponse::relation).containsExactly("FOLLOWER", "FOLLOWING");
		assertThat(queryService.mentionCandidates(ME, RECORD, "작")).extracting(MentionCandidateResponse::relation)
			.containsExactly("COMMENTER");
	}
}
