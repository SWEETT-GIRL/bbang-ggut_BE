package sweetgirl.croissant.breadlog.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.service.ReportedTargetReader;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPhotoResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordTagResponse;
import sweetgirl.croissant.breadlog.dto.response.StoreBreadRecordListResponse;
import sweetgirl.croissant.breadlog.dto.response.StoreBreadRecordResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.enums.BreadRecordSort;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordLikeRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordLikeRepository.RecordLikeCountProjection;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.comment.repository.BreadRecordCommentRepository;
import sweetgirl.croissant.comment.repository.BreadRecordCommentRepository.RecordCommentCountProjection;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.profile.dto.response.CharacterFace;
import sweetgirl.croissant.profile.service.CharacterFaceReader;
import sweetgirl.croissant.store.service.StoreThumbnailReader;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BreadRecordQueryService {

	private static final Sort RECORD_SORT = Sort.by(
		Sort.Order.desc("eatenAt"),
		Sort.Order.desc("createdAt"),
		Sort.Order.desc("id"));

	private final MemberReader memberReader;
	private final BreadRecordRepository breadRecordRepository;
	private final BreadRecordPhotoRepository photoRepository;
	private final BreadRecordTagRepository tagRepository;
	private final TowerBreadPicker towerBreadPicker;
	private final ReportedTargetReader reportedTargetReader;
	private final BreadRecordLikeRepository breadRecordLikeRepository;
	private final BreadRecordCommentRepository commentRepository;
	private final CharacterFaceReader characterFaceReader;
	private final StoreThumbnailReader storeThumbnailReader;

	public SliceResponse<BreadRecordResponse> getRecords(
			Long memberId,
			LocalDateTime from,
			LocalDateTime to,
			Long storeId,
			int page,
			int size) {
		memberReader.getMember(memberId);
		validateDateRange(from, to);
		validatePageRequest(page, size);
		Slice<BreadRecord> recordSlice = breadRecordRepository.findRecords(
			memberId,
			from,
			to,
			storeId,
			PageRequest.of(page, size, RECORD_SORT));
		return new SliceResponse<>(buildResponses(recordSlice.getContent()), recordSlice.hasNext());
	}

	/** 정렬은 최신순(기본, 구버전 앱) · 추천순. 전체 수는 목록과 같은 조건으로 센다(내가 신고한 것은 목록에서만 빠진다). */
	public StoreBreadRecordListResponse getStorePublicRecords(
			Long storeId,
			Long viewerMemberId,
			BreadRecordSort sort,
			int page,
			int size) {
		validatePageRequest(page, size);
		List<BreadRecord> records;
		if (sort == BreadRecordSort.RECOMMENDED) {
			LocalDateTime now = LocalDateTime.now();
			List<Long> ids = breadRecordRepository.findPublicIdsByStoreIdRecommended(storeId, viewerMemberId,
				now.minusDays(7), now.minusDays(30), size + 1, page * size);
			Map<Long, BreadRecord> byId = breadRecordRepository.findAllById(ids).stream()
				.collect(Collectors.toMap(BreadRecord::getId, Function.identity()));
			records = ids.stream().map(byId::get).filter(record -> record != null).toList();
		} else {
			// 하나 더 읽어 다음 페이지가 있는지 본다 — 가게 리뷰 목록과 같은 방식.
			records = breadRecordRepository.findPublicByStoreId(storeId, viewerMemberId,
				PageRequest.of(page, size + 1));
		}
		boolean hasNext = records.size() > size;
		long totalCount = breadRecordRepository.countPublicByStoreId(storeId, viewerMemberId);
		// 신고한 사람 화면에선 그 빵로그가 바로 빠진다(기각되면 다시 보임)
		List<BreadRecord> pageRecords = ReportedTargetReader.without(
			hasNext ? records.subList(0, size) : records, BreadRecord::getId,
			reportedTargetReader.reportedIds(viewerMemberId, AbuseReportTargetType.RECORD));
		if (pageRecords.isEmpty()) {
			// 한 쪽을 전부 내가 신고했어도 다음 쪽은 있을 수 있다
			return new StoreBreadRecordListResponse(List.of(), hasNext, totalCount);
		}
		List<Long> recordIds = pageRecords.stream().map(BreadRecord::getId).toList();
		Map<Long, List<BreadRecordPhotoResponse>> photosByRecordId = loadPhotos(recordIds);
		Map<Long, List<BreadRecordTagResponse>> tagsByRecordId = loadTags(recordIds);
		Map<Long, Long> likeCounts = likeCountsOf(recordIds);
		Map<Long, Long> commentCounts = commentCountsOf(recordIds);
		Map<Long, CharacterFace> faces = characterFaceReader.facesOf(
			pageRecords.stream().map(record -> record.getMember().getId()).distinct().toList());
		List<StoreBreadRecordResponse> content = pageRecords.stream()
			.map(record -> StoreBreadRecordResponse.of(
				record,
				photosByRecordId.getOrDefault(record.getId(), List.of()),
				tagsByRecordId.getOrDefault(record.getId(), List.of()),
				likeCounts.getOrDefault(record.getId(), 0L),
				commentCounts.getOrDefault(record.getId(), 0L),
				faces.get(record.getMember().getId())))
			.toList();
		return new StoreBreadRecordListResponse(content, hasNext, totalCount);
	}

	private List<BreadRecordResponse> buildResponses(List<BreadRecord> records) {
		if (records.isEmpty()) {
			return List.of();
		}
		List<Long> recordIds = records.stream().map(BreadRecord::getId).toList();
		Map<Long, List<BreadRecordPhotoResponse>> photosByRecordId = loadPhotos(recordIds);
		Map<Long, List<BreadRecordTagResponse>> tagsByRecordId = loadTags(recordIds);
		Map<Long, Long> likeCounts = likeCountsOf(recordIds);
		Map<Long, Long> commentCounts = commentCountsOf(recordIds);

		Map<Long, String> storeThumbnails = storeThumbnailReader.thumbnailsOf(records.stream()
			.filter(record -> record.getStore() != null)
			.map(record -> record.getStore().getId()).distinct().toList());

		return records.stream()
			.map(record -> BreadRecordResponse.of(
				record,
				photosByRecordId.getOrDefault(record.getId(), List.of()),
				tagsByRecordId.getOrDefault(record.getId(), List.of()),
				towerBreadPicker.resolve(record.getId(), record.getTowerBread()),
				likeCounts.getOrDefault(record.getId(), 0L),
				commentCounts.getOrDefault(record.getId(), 0L),
				record.getStore() == null ? null : storeThumbnails.get(record.getStore().getId())))
			.toList();
	}

	private Map<Long, Long> likeCountsOf(List<Long> recordIds) {
		return breadRecordLikeRepository.countByRecordIdIn(recordIds).stream()
			.collect(Collectors.toMap(RecordLikeCountProjection::getRecordId, RecordLikeCountProjection::getLikeCount));
	}

	private Map<Long, Long> commentCountsOf(List<Long> recordIds) {
		return commentRepository.countVisibleByRecordIdIn(recordIds).stream()
			.collect(Collectors.toMap(RecordCommentCountProjection::getRecordId,
				RecordCommentCountProjection::getCommentCount));
	}

	private Map<Long, List<BreadRecordPhotoResponse>> loadPhotos(List<Long> recordIds) {
		return photoRepository
			.findByRecordIdInOrderByRecordIdAscSortOrderAscIdAsc(recordIds)
			.stream()
			.collect(Collectors.groupingBy(
				photo -> photo.getRecord().getId(),
				Collectors.mapping(BreadRecordPhotoResponse::from, Collectors.toList())));
	}

	private Map<Long, List<BreadRecordTagResponse>> loadTags(List<Long> recordIds) {
		return tagRepository
			.findByRecordIdInWithPhoto(recordIds)
			.stream()
			.collect(Collectors.groupingBy(
				tag -> tag.getRecord().getId(),
				Collectors.mapping(BreadRecordTagResponse::from, Collectors.toList())));
	}

	private void validateDateRange(LocalDateTime from, LocalDateTime to) {
		if (from != null && to != null && !from.isBefore(to)) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_DATE_RANGE);
		}
	}

	private void validatePageRequest(int page, int size) {
		if (page < 0 || size < 1 || size > 100) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_PAGE_REQUEST);
		}
	}

}
