package sweetgirl.croissant.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.service.BreadRecordVisibilityPolicy;
import sweetgirl.croissant.comment.code.CommentErrorCode;
import sweetgirl.croissant.comment.dto.request.CommentCreateRequest;
import sweetgirl.croissant.comment.dto.request.MentionRequest;
import sweetgirl.croissant.comment.dto.response.CommentResponse;
import sweetgirl.croissant.comment.entity.BreadRecordComment;
import sweetgirl.croissant.comment.entity.CommentMention;
import sweetgirl.croissant.comment.exception.CommentException;
import sweetgirl.croissant.comment.repository.BreadRecordCommentRepository;
import sweetgirl.croissant.comment.repository.CommentMentionRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.member.service.SocialMemberReader;
import sweetgirl.croissant.profile.dto.response.CharacterFace;
import sweetgirl.croissant.profile.service.CharacterFaceReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommentCommandService")
class CommentCommandServiceTest {

	private static final Long AUTHOR = 1L;
	private static final Long ME = 2L;
	private static final Long FRIEND = 3L;
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
	private CommentContentPolicy contentPolicy;
	@Mock
	private CommunityAgreementService communityAgreementService;
	@Mock
	private MemberReader memberReader;
	@Mock
	private MemberRestrictionReader memberRestrictionReader;
	@Mock
	private SocialMemberReader socialMemberReader;
	@Mock
	private MemberBlockRepository memberBlockRepository;
	@Mock
	private CharacterFaceReader characterFaceReader;

	@InjectMocks
	private CommentCommandService commandService;

	private Member member(Long id) {
		Member member = Member.builder().nickname("회원" + id).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private BreadRecord publicRecord() {
		BreadRecord record = BreadRecord.builder().member(member(AUTHOR)).storeName("밀도").isPublic(true).build();
		ReflectionTestUtils.setField(record, "id", RECORD);
		return record;
	}

	@BeforeEach
	void setUp() {
		lenient().when(memberReader.getMember(ME)).thenReturn(member(ME));
		lenient().when(characterFaceReader.faceOf(ME)).thenReturn(CharacterFace.NONE);
		lenient().when(commentRepository.save(any())).thenAnswer(invocation -> {
			BreadRecordComment comment = invocation.getArgument(0);
			ReflectionTestUtils.setField(comment, "id", 100L);
			return comment;
		});
		lenient().when(mentionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	@DisplayName("공개 빵로그에 댓글을 남기고 멘션을 같이 저장한다")
	void creates() {
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD)).willReturn(Optional.of(publicRecord()));
		given(commentRepository.countByMemberIdAndCreatedAtAfter(eq(ME), any())).willReturn(0L);
		given(socialMemberReader.findSocial(FRIEND)).willReturn(Optional.of(member(FRIEND)));
		given(memberBlockRepository.existsBetween(anyLong(), anyLong())).willReturn(false);

		CommentResponse response = commandService.create(ME, RECORD,
			new CommentCreateRequest(" @회원3 소금빵 최고 ", List.of(new MentionRequest(FRIEND, 0, 4))));

		then(communityAgreementService).should().validateAgreed(ME);
		then(contentPolicy).should().validateComment("@회원3 소금빵 최고");
		assertThat(response.content()).isEqualTo("@회원3 소금빵 최고");
		assertThat(response.isMine()).isTrue();
		assertThat(response.isAuthor()).isFalse();
		assertThat(response.mentions()).containsExactly(new CommentResponse.MentionResponse(FRIEND, 0, 4));
	}

	@Test
	@DisplayName("30초에 3개를 넘기면 429 TOO_FAST")
	void tooFast() {
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD)).willReturn(Optional.of(publicRecord()));
		given(commentRepository.countByMemberIdAndCreatedAtAfter(eq(ME), any()))
			.willReturn((long)CommentCommandService.BURST_LIMIT);

		assertThatThrownBy(() -> commandService.create(ME, RECORD, new CommentCreateRequest("빵", null)))
			.isInstanceOf(CommentException.class)
			.extracting("errorCode").isEqualTo(CommentErrorCode.COMMENT_TOO_FAST);
		then(commentRepository).should(org.mockito.Mockito.never()).save(any());
	}

	@Test
	@DisplayName("규칙 동의 전 · 작성 제한 · 비공개 기록은 각자 코드로 막힌다")
	void rejectedEarly() {
		willThrow(new CommentException(CommentErrorCode.COMMUNITY_AGREEMENT_REQUIRED))
			.given(communityAgreementService).validateAgreed(ME);
		assertThatThrownBy(() -> commandService.create(ME, RECORD, new CommentCreateRequest("빵", null)))
			.isInstanceOf(CommentException.class)
			.extracting("errorCode").isEqualTo(CommentErrorCode.COMMUNITY_AGREEMENT_REQUIRED);

		org.mockito.Mockito.reset(communityAgreementService);
		BreadRecord privateRecord = publicRecord();
		privateRecord.changeVisibility(false);
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD)).willReturn(Optional.of(privateRecord));
		assertThatThrownBy(() -> commandService.create(ME, RECORD, new CommentCreateRequest("빵", null)))
			.isInstanceOf(BreadLogException.class)
			.extracting("errorCode").isEqualTo(BreadLogErrorCode.BREAD_RECORD_NOT_VISIBLE);
		then(commentRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("멘션은 3명까지, 같은 사람 두 번 · 본문 밖 오프셋 · 탈퇴 · 차단 관계면 400")
	void mentionsRejected() {
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD)).willReturn(Optional.of(publicRecord()));
		given(commentRepository.countByMemberIdAndCreatedAtAfter(eq(ME), any())).willReturn(0L);

		List<MentionRequest> four = List.of(new MentionRequest(3L, 0, 1), new MentionRequest(4L, 1, 1),
			new MentionRequest(5L, 2, 1), new MentionRequest(6L, 3, 1));
		assertThatThrownBy(() -> commandService.create(ME, RECORD, new CommentCreateRequest("가나다라", four)))
			.extracting("errorCode").isEqualTo(CommentErrorCode.COMMENT_MENTION_LIMIT);

		assertThatThrownBy(() -> commandService.create(ME, RECORD,
			new CommentCreateRequest("가나", List.of(new MentionRequest(FRIEND, 1, 5)))))
			.extracting("errorCode").isEqualTo(CommentErrorCode.COMMENT_MENTION_INVALID);

		given(socialMemberReader.findSocial(FRIEND)).willReturn(Optional.empty());
		assertThatThrownBy(() -> commandService.create(ME, RECORD,
			new CommentCreateRequest("가나", List.of(new MentionRequest(FRIEND, 0, 2)))))
			.extracting("errorCode").isEqualTo(CommentErrorCode.COMMENT_MENTION_INVALID);

		given(socialMemberReader.findSocial(FRIEND)).willReturn(Optional.of(member(FRIEND)));
		given(memberBlockRepository.existsBetween(ME, FRIEND)).willReturn(true);
		assertThatThrownBy(() -> commandService.create(ME, RECORD,
			new CommentCreateRequest("가나", List.of(new MentionRequest(FRIEND, 0, 2)))))
			.extracting("errorCode").isEqualTo(CommentErrorCode.COMMENT_MENTION_INVALID);
		then(commentRepository).should(org.mockito.Mockito.never()).save(any());
	}

	@Test
	@DisplayName("지우기: 내 댓글은 AUTHOR, 내 빵로그의 남의 댓글은 RECORD_OWNER, 남의 빵로그의 남의 댓글은 403")
	void deletes() {
		BreadRecord record = publicRecord();
		BreadRecordComment mine = BreadRecordComment.of(record, member(ME), "내 댓글");
		given(commentRepository.findActiveByIdWithRecord(100L)).willReturn(Optional.of(mine));
		commandService.delete(ME, 100L);
		assertThat(mine.getDeletedBy()).isEqualTo(BreadRecordComment.DELETED_BY_AUTHOR);

		BreadRecordComment others = BreadRecordComment.of(record, member(FRIEND), "남 댓글");
		given(commentRepository.findActiveByIdWithRecord(101L)).willReturn(Optional.of(others));
		lenient().when(memberReader.getMember(AUTHOR)).thenReturn(member(AUTHOR));
		commandService.delete(AUTHOR, 101L);
		assertThat(others.getDeletedBy()).isEqualTo(BreadRecordComment.DELETED_BY_RECORD_OWNER);

		BreadRecordComment untouchable = BreadRecordComment.of(record, member(FRIEND), "남 댓글");
		given(commentRepository.findActiveByIdWithRecord(102L)).willReturn(Optional.of(untouchable));
		assertThatThrownBy(() -> commandService.delete(ME, 102L))
			.isInstanceOf(CommentException.class)
			.extracting("errorCode").isEqualTo(CommentErrorCode.COMMENT_DELETE_DENIED);
		assertThat(untouchable.isDeleted()).isFalse();
	}
}
