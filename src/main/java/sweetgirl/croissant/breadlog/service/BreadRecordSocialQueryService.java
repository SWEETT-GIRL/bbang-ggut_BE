package sweetgirl.croissant.breadlog.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordDetailResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPhotoResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordTagResponse;
import sweetgirl.croissant.breadlog.dto.response.MemberBreadRecordResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordLike;
import sweetgirl.croissant.breadlog.enums.BreadRecordSort;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordLikeRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordLikeRepository.RecordLikeCountProjection;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.comment.repository.BreadRecordCommentRepository;
import sweetgirl.croissant.comment.repository.BreadRecordCommentRepository.RecordCommentCountProjection;
import sweetgirl.croissant.comment.service.CommentQueryService;
import sweetgirl.croissant.follow.dto.response.FollowMemberResponse;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.SocialMemberReader;
import sweetgirl.croissant.profile.code.ProfileErrorCode;
import sweetgirl.croissant.profile.exception.ProfileException;
import sweetgirl.croissant.profile.service.AuthorCardReader;
import sweetgirl.croissant.profile.service.MemberSearchQueryService;

/** 남에게 보이는 빵로그 — 프로필 목록 · 상세 · 좋아요 누른 사람. 내 타임라인은 {@link BreadRecordQueryService}. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BreadRecordSocialQueryService {

	static final int MAX_PAGE_SIZE = 50;

	private final BreadRecordRepository breadRecordRepository;
	private final BreadRecordLikeRepository breadRecordLikeRepository;
	private final BreadRecordPhotoRepository photoRepository;
	private final BreadRecordTagRepository tagRepository;
	private final BreadRecordVisibilityPolicy visibilityPolicy;
	private final SocialMemberReader socialMemberReader;
	private final MemberBlockRepository memberBlockRepository;
	private final AuthorCardReader authorCardReader;
	private final MemberSearchQueryService memberSearchQueryService;
	private final TowerBreadPicker towerBreadPicker;
	private final BreadRecordCommentRepository commentRepository;
	private final CommentQueryService commentQueryService;

	/**
	 * 프로필의 공개 빵로그. 본인이면 숨긴 것도 "검토 중"으로 보인다. 상대가 게스트 · 봇 · 탈퇴면 404,
	 * 보는 사람과 차단 관계면 403 — 프로필과 같은 규칙.
	 */
	public SliceResponse<MemberBreadRecordResponse> getMemberRecords(Long viewerId, Long memberId,
			BreadRecordSort sort, int page, int size) {
		socialMemberReader.findSocial(memberId)
			.orElseThrow(() -> new ProfileException(ProfileErrorCode.PROFILE_MEMBER_NOT_FOUND));
		boolean isMe = memberId.equals(viewerId);
		if (!isMe && viewerId != null && memberBlockRepository.existsBetween(viewerId, memberId)) {
			throw new ProfileException(ProfileErrorCode.PROFILE_BLOCKED);
		}
		int safePage = Math.max(page, 0);
		int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		List<BreadRecord> records;
		boolean hasNext;
		if (sort == BreadRecordSort.RECOMMENDED) {
			LocalDateTime now = LocalDateTime.now();
			List<Long> ids = breadRecordRepository.findPublicIdsByMemberIdRecommended(
				memberId, isMe, now.minusDays(7), now.minusDays(30), safeSize + 1, safePage * safeSize);
			hasNext = ids.size() > safeSize;
			List<Long> pageIds = hasNext ? ids.subList(0, safeSize) : ids;
			Map<Long, BreadRecord> byId = breadRecordRepository.findAllById(pageIds).stream()
				.collect(Collectors.toMap(BreadRecord::getId, Function.identity()));
			records = pageIds.stream().map(byId::get).filter(record -> record != null).toList();
		} else {
			Slice<BreadRecord> slice = breadRecordRepository.findPublicByMemberIdLatest(
				memberId, isMe, PageRequest.of(safePage, safeSize));
			records = slice.getContent();
			hasNext = slice.hasNext();
		}
		return new SliceResponse<>(toRows(viewerId, records), hasNext);
	}

	/** 남의 것은 공개 · 숨김 아님 · 차단 아님일 때만. 아니면 403 "볼 수 없어요". 내 것은 비공개여도 본다. */
	public BreadRecordDetailResponse getDetail(Long viewerId, Long recordId) {
		BreadRecord record = breadRecordRepository.findByIdAndDeletedAtIsNull(recordId)
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.BREAD_RECORD_NOT_FOUND));
		visibilityPolicy.validateCanView(viewerId, record);
		List<Long> ids = List.of(record.getId());
		boolean likedByMe = viewerId != null
			&& breadRecordLikeRepository.existsByIdRecordIdAndIdMemberId(record.getId(), viewerId);
		return BreadRecordDetailResponse.of(
			record,
			authorCardReader.read(viewerId, record.getMember()),
			loadPhotos(ids).getOrDefault(record.getId(), List.of()),
			loadTags(ids).getOrDefault(record.getId(), List.of()),
			towerBreadPicker.resolve(record.getId(), record.getTowerBread()),
			breadRecordLikeRepository.countByIdRecordId(record.getId()),
			likedByMe,
			commentRepository.countVisibleByRecordId(record.getId()),
			commentQueryService.firstComment(viewerId, record));
	}

	/** 좋아요 누른 사람. 기록을 볼 수 있어야 목록도 본다. */
	public SliceResponse<FollowMemberResponse> getLikers(Long viewerId, Long recordId, int page, int size) {
		BreadRecord record = breadRecordRepository.findByIdAndDeletedAtIsNull(recordId)
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.BREAD_RECORD_NOT_FOUND));
		visibilityPolicy.validateCanView(viewerId, record);
		Slice<BreadRecordLike> likes = breadRecordLikeRepository.findLikers(record.getId(), viewerId,
			PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
		List<FollowMemberResponse> content = memberSearchQueryService.withRelations(viewerId,
			likes.getContent().stream().map(BreadRecordLike::getMember).toList());
		return new SliceResponse<>(content, likes.hasNext());
	}

	private List<MemberBreadRecordResponse> toRows(Long viewerId, List<BreadRecord> records) {
		if (records.isEmpty()) {
			return List.of();
		}
		List<Long> ids = records.stream().map(BreadRecord::getId).toList();
		Map<Long, List<BreadRecordPhotoResponse>> photos = loadPhotos(ids);
		Map<Long, List<BreadRecordTagResponse>> tags = loadTags(ids);
		Map<Long, Long> likeCounts = breadRecordLikeRepository.countByRecordIdIn(ids).stream()
			.collect(Collectors.toMap(RecordLikeCountProjection::getRecordId, RecordLikeCountProjection::getLikeCount));
		Set<Long> likedByMe = viewerId == null ? Set.of()
			: Set.copyOf(breadRecordLikeRepository.findRecordIdsLikedBy(viewerId, ids));
		Map<Long, Long> commentCounts = commentRepository.countVisibleByRecordIdIn(ids).stream()
			.collect(Collectors.toMap(RecordCommentCountProjection::getRecordId,
				RecordCommentCountProjection::getCommentCount));
		return records.stream()
			.map(record -> MemberBreadRecordResponse.of(
				record,
				photos.getOrDefault(record.getId(), List.of()),
				tags.getOrDefault(record.getId(), List.of()),
				likeCounts.getOrDefault(record.getId(), 0L),
				commentCounts.getOrDefault(record.getId(), 0L),
				likedByMe.contains(record.getId())))
			.toList();
	}

	private Map<Long, List<BreadRecordPhotoResponse>> loadPhotos(List<Long> recordIds) {
		return photoRepository.findByRecordIdInOrderByRecordIdAscSortOrderAscIdAsc(recordIds).stream()
			.collect(Collectors.groupingBy(photo -> photo.getRecord().getId(),
				Collectors.mapping(BreadRecordPhotoResponse::from, Collectors.toList())));
	}

	private Map<Long, List<BreadRecordTagResponse>> loadTags(List<Long> recordIds) {
		return tagRepository.findByRecordIdInWithPhoto(recordIds).stream()
			.collect(Collectors.groupingBy(tag -> tag.getRecord().getId(),
				Collectors.mapping(BreadRecordTagResponse::from, Collectors.toList())));
	}
}
