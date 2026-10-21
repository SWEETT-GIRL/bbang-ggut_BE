package sweetgirl.croissant.breadlog.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPhotoResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordTagResponse;
import sweetgirl.croissant.breadlog.dto.response.StoreBreadRecordResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.service.MemberReader;

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

	public SliceResponse<StoreBreadRecordResponse> getStorePublicRecords(
			Long storeId,
			Long viewerMemberId,
			int page,
			int size) {
		validatePageRequest(page, size);
		// 하나 더 읽어 다음 페이지가 있는지 본다 — 가게 리뷰 목록과 같은 방식.
		List<BreadRecord> records = breadRecordRepository.findPublicByStoreId(
			storeId,
			viewerMemberId,
			PageRequest.of(page, size + 1));
		boolean hasNext = records.size() > size;
		List<BreadRecord> pageRecords = hasNext ? records.subList(0, size) : records;
		if (pageRecords.isEmpty()) {
			return new SliceResponse<>(List.of(), false);
		}
		List<Long> recordIds = pageRecords.stream().map(BreadRecord::getId).toList();
		Map<Long, List<BreadRecordPhotoResponse>> photosByRecordId = loadPhotos(recordIds);
		Map<Long, List<BreadRecordTagResponse>> tagsByRecordId = loadTags(recordIds);
		List<StoreBreadRecordResponse> content = pageRecords.stream()
			.map(record -> StoreBreadRecordResponse.of(
				record,
				photosByRecordId.getOrDefault(record.getId(), List.of()),
				tagsByRecordId.getOrDefault(record.getId(), List.of())))
			.toList();
		return new SliceResponse<>(content, hasNext);
	}

	private List<BreadRecordResponse> buildResponses(List<BreadRecord> records) {
		if (records.isEmpty()) {
			return List.of();
		}
		List<Long> recordIds = records.stream().map(BreadRecord::getId).toList();
		Map<Long, List<BreadRecordPhotoResponse>> photosByRecordId = loadPhotos(recordIds);
		Map<Long, List<BreadRecordTagResponse>> tagsByRecordId = loadTags(recordIds);

		return records.stream()
			.map(record -> BreadRecordResponse.of(
				record,
				photosByRecordId.getOrDefault(record.getId(), List.of()),
				tagsByRecordId.getOrDefault(record.getId(), List.of()),
				towerBreadPicker.resolve(record.getId(), record.getTowerBread())))
			.toList();
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
