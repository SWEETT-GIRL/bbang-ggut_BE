package sweetgirl.croissant.breadlog.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPhotoResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordTagResponse;
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
		Specification<BreadRecord> specification = Specification
			.where(ownedBy(memberId))
			.and(notDeleted());
		if (from != null) {
			specification = specification.and(eatenAtOrAfter(from));
		}
		if (to != null) {
			specification = specification.and(eatenBefore(to));
		}
		if (storeId != null) {
			specification = specification.and(atStore(storeId));
		}
		Page<BreadRecord> recordPage = breadRecordRepository.findAll(
			specification,
			PageRequest.of(page, size, RECORD_SORT));
		return new SliceResponse<>(buildResponses(recordPage.getContent()), recordPage.hasNext());
	}

	private List<BreadRecordResponse> buildResponses(List<BreadRecord> records) {
		if (records.isEmpty()) {
			return List.of();
		}
		List<Long> recordIds = records.stream().map(BreadRecord::getId).toList();
		Map<Long, List<BreadRecordPhotoResponse>> photosByRecordId = photoRepository
			.findByRecordIdInOrderByRecordIdAscSortOrderAscIdAsc(recordIds)
			.stream()
			.collect(Collectors.groupingBy(
				photo -> photo.getRecord().getId(),
				Collectors.mapping(BreadRecordPhotoResponse::from, Collectors.toList())));
		Map<Long, List<BreadRecordTagResponse>> tagsByRecordId = tagRepository
			.findByRecordIdInWithPhoto(recordIds)
			.stream()
			.collect(Collectors.groupingBy(
				tag -> tag.getRecord().getId(),
				Collectors.mapping(BreadRecordTagResponse::from, Collectors.toList())));

		return records.stream()
			.map(record -> BreadRecordResponse.of(
				record,
				photosByRecordId.getOrDefault(record.getId(), List.of()),
				tagsByRecordId.getOrDefault(record.getId(), List.of())))
			.toList();
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

	private Specification<BreadRecord> ownedBy(Long memberId) {
		return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("member").get("id"), memberId);
	}

	private Specification<BreadRecord> notDeleted() {
		return (root, query, criteriaBuilder) -> criteriaBuilder.isNull(root.get("deletedAt"));
	}

	private Specification<BreadRecord> eatenAtOrAfter(LocalDateTime from) {
		return (root, query, criteriaBuilder) -> criteriaBuilder.greaterThanOrEqualTo(root.get("eatenAt"), from);
	}

	private Specification<BreadRecord> eatenBefore(LocalDateTime to) {
		return (root, query, criteriaBuilder) -> criteriaBuilder.lessThan(root.get("eatenAt"), to);
	}

	private Specification<BreadRecord> atStore(Long storeId) {
		return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("store").get("id"), storeId);
	}
}
