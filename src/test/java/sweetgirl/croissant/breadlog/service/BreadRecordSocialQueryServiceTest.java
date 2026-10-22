package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordDetailResponse;
import sweetgirl.croissant.breadlog.dto.response.MemberBreadRecordResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.enums.BreadRecordSort;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordLikeRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordLikeRepository.RecordLikeCountProjection;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.SocialMemberReader;
import sweetgirl.croissant.profile.code.ProfileErrorCode;
import sweetgirl.croissant.profile.dto.response.AuthorCardResponse;
import sweetgirl.croissant.profile.exception.ProfileException;
import sweetgirl.croissant.profile.service.AuthorCardReader;
import sweetgirl.croissant.profile.service.MemberSearchQueryService;

@ExtendWith(MockitoExtension.class)
@DisplayName("BreadRecordSocialQueryService")
class BreadRecordSocialQueryServiceTest {

	private static final Long AUTHOR = 1L;
	private static final Long VIEWER = 2L;

	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private BreadRecordLikeRepository breadRecordLikeRepository;
	@Mock
	private BreadRecordPhotoRepository photoRepository;
	@Mock
	private BreadRecordTagRepository tagRepository;
	@Mock
	private BreadRecordVisibilityPolicy visibilityPolicy;
	@Mock
	private SocialMemberReader socialMemberReader;
	@Mock
	private MemberBlockRepository memberBlockRepository;
	@Mock
	private AuthorCardReader authorCardReader;
	@Mock
	private MemberSearchQueryService memberSearchQueryService;
	@Mock
	private TowerBreadPicker towerBreadPicker;

	@InjectMocks
	private BreadRecordSocialQueryService queryService;

	private Member author() {
		Member author = Member.builder().nickname("작성자").build();
		ReflectionTestUtils.setField(author, "id", AUTHOR);
		return author;
	}

	private BreadRecord record(Long id, boolean isPublic) {
		BreadRecord record = BreadRecord.builder().member(author()).storeName("밀도").isPublic(isPublic)
			.eatenAt(LocalDateTime.of(2026, 10, 1, 12, 0)).build();
		ReflectionTestUtils.setField(record, "id", id);
		return record;
	}

	private RecordLikeCountProjection likeCount(Long recordId, long count) {
		return new RecordLikeCountProjection() {
			@Override
			public Long getRecordId() {
				return recordId;
			}

			@Override
			public long getLikeCount() {
				return count;
			}
		};
	}

	@Test
	@DisplayName("최신순: 공개 기록을 읽고 줄마다 좋아요 수 · likedByMe 를 한 번에 채운다")
	void latest() {
		given(socialMemberReader.findSocial(AUTHOR)).willReturn(Optional.of(author()));
		given(memberBlockRepository.existsBetween(VIEWER, AUTHOR)).willReturn(false);
		given(breadRecordRepository.findPublicByMemberIdLatest(eq(AUTHOR), eq(false), any()))
			.willReturn(new SliceImpl<>(List.of(record(10L, true), record(11L, true)), Pageable.ofSize(20), true));
		given(breadRecordLikeRepository.countByRecordIdIn(List.of(10L, 11L))).willReturn(List.of(likeCount(10L, 3)));
		given(breadRecordLikeRepository.findRecordIdsLikedBy(VIEWER, List.of(10L, 11L))).willReturn(List.of(11L));

		SliceResponse<MemberBreadRecordResponse> result = queryService.getMemberRecords(VIEWER, AUTHOR,
			BreadRecordSort.LATEST, 0, 20);

		assertThat(result.hasNext()).isTrue();
		assertThat(result.content()).extracting(MemberBreadRecordResponse::likeCount).containsExactly(3L, 0L);
		assertThat(result.content()).extracting(MemberBreadRecordResponse::likedByMe).containsExactly(false, true);
		assertThat(result.content().get(0).hiddenStatus()).isNull();
	}

	@Test
	@DisplayName("추천순: id 를 점수순으로 읽고(하나 더), 엔티티는 그 순서로 다시 맞춘다. 본인은 숨긴 것도 포함한다")
	void recommended() {
		given(socialMemberReader.findSocial(AUTHOR)).willReturn(Optional.of(author()));
		given(
			breadRecordRepository.findPublicIdsByMemberIdRecommended(eq(AUTHOR), eq(true), any(), any(), eq(3), eq(0)))
			.willReturn(List.of(11L, 10L, 12L));
		BreadRecord hidden = record(11L, true);
		hidden.hide("신고 3건", "SYSTEM");
		given(breadRecordRepository.findAllById(List.of(11L, 10L))).willReturn(List.of(record(10L, true), hidden));
		given(breadRecordLikeRepository.countByRecordIdIn(List.of(11L, 10L))).willReturn(List.of());
		given(breadRecordLikeRepository.findRecordIdsLikedBy(AUTHOR, List.of(11L, 10L))).willReturn(List.of());

		SliceResponse<MemberBreadRecordResponse> result = queryService.getMemberRecords(AUTHOR, AUTHOR,
			BreadRecordSort.RECOMMENDED, 0, 2);

		assertThat(result.hasNext()).isTrue();
		assertThat(result.content()).extracting(MemberBreadRecordResponse::id).containsExactly(11L, 10L);
		assertThat(result.content().get(0).hiddenStatus()).isEqualTo(MemberBreadRecordResponse.UNDER_REVIEW);
		then(memberBlockRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("상대가 없으면 404 PROFILE404_MEMBER, 차단 관계면 403 PROFILE403_BLOCKED")
	void listRejected() {
		given(socialMemberReader.findSocial(AUTHOR)).willReturn(Optional.empty());
		assertThatThrownBy(() -> queryService.getMemberRecords(VIEWER, AUTHOR, BreadRecordSort.LATEST, 0, 20))
			.isInstanceOf(ProfileException.class)
			.extracting("errorCode").isEqualTo(ProfileErrorCode.PROFILE_MEMBER_NOT_FOUND);

		given(socialMemberReader.findSocial(AUTHOR)).willReturn(Optional.of(author()));
		given(memberBlockRepository.existsBetween(VIEWER, AUTHOR)).willReturn(true);
		assertThatThrownBy(() -> queryService.getMemberRecords(VIEWER, AUTHOR, BreadRecordSort.LATEST, 0, 20))
			.isInstanceOf(ProfileException.class)
			.extracting("errorCode").isEqualTo(ProfileErrorCode.PROFILE_BLOCKED);
		then(breadRecordRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("상세: 글쓴이 줄 · 좋아요 수 · likedByMe · 댓글 자리(0 · null) · 같이 간 사람 자리(빈 배열)")
	void detail() {
		BreadRecord record = record(10L, true);
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(record));
		AuthorCardResponse card = new AuthorCardResponse(AUTHOR, "작성자", "dog", "world/dog/front.png", 12, "성수동 빵순이",
			false, true, false);
		given(authorCardReader.read(VIEWER, record.getMember())).willReturn(card);
		given(breadRecordLikeRepository.existsByIdRecordIdAndIdMemberId(10L, VIEWER)).willReturn(true);
		given(breadRecordLikeRepository.countByIdRecordId(10L)).willReturn(5L);
		given(towerBreadPicker.resolve(10L, null)).willReturn("소금빵");

		BreadRecordDetailResponse response = queryService.getDetail(VIEWER, 10L);

		then(visibilityPolicy).should().validateCanView(VIEWER, record);
		assertThat(response.author()).isEqualTo(card);
		assertThat(response.likeCount()).isEqualTo(5);
		assertThat(response.likedByMe()).isTrue();
		assertThat(response.towerBread()).isEqualTo("소금빵");
		assertThat(response.commentCount()).isZero();
		assertThat(response.firstComment()).isNull();
		assertThat(response.companions()).isEmpty();
		assertThat(response.hiddenStatus()).isNull();
	}

	@Test
	@DisplayName("상세: 없으면 404, 볼 수 없으면 정책이 던진 403 그대로")
	void detailRejected() {
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.empty());
		assertThatThrownBy(() -> queryService.getDetail(VIEWER, 10L))
			.isInstanceOf(BreadLogException.class)
			.extracting("errorCode").isEqualTo(BreadLogErrorCode.BREAD_RECORD_NOT_FOUND);

		BreadRecord record = record(10L, false);
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(record));
		willThrow(new BreadLogException(BreadLogErrorCode.BREAD_RECORD_NOT_VISIBLE))
			.given(visibilityPolicy).validateCanView(VIEWER, record);
		assertThatThrownBy(() -> queryService.getDetail(VIEWER, 10L))
			.isInstanceOf(BreadLogException.class)
			.extracting("errorCode").isEqualTo(BreadLogErrorCode.BREAD_RECORD_NOT_VISIBLE);
		then(authorCardReader).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("좋아요 누른 사람: 기록을 볼 수 있어야 하고, 줄의 팔로우 관계는 검색과 같은 방식으로 채운다")
	void likers() {
		BreadRecord record = record(10L, true);
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(record));
		given(breadRecordLikeRepository.findLikers(eq(10L), eq(VIEWER), any()))
			.willReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));
		given(memberSearchQueryService.withRelations(VIEWER, List.of())).willReturn(List.of());

		assertThat(queryService.getLikers(VIEWER, 10L, 0, 20).content()).isEmpty();
		then(visibilityPolicy).should().validateCanView(VIEWER, record);
	}
}
