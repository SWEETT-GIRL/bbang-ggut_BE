package sweetgirl.croissant.monthlyreport.service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot.CourseState;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot.PhotoState;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot.RecordState;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot.RelationState;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot.TagState;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportSourceHistory;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportSourceHistoryRepository;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MonthlyReportSourceReader {

	private final MonthlyReportSourceHistoryRepository repository;
	private final ObjectMapper mapper;

	public MonthlyReportSourceReader(MonthlyReportSourceHistoryRepository repository, ObjectMapper mapper) {
		this.repository = repository;
		this.mapper = mapper.copy().registerModule(new JavaTimeModule());
	}

	public MonthlyReportSourceSnapshot read(Long memberId, String yearMonth) {
		YearMonth month = MonthlyReportMonth.parse(yearMonth);
		LocalDateTime from = month.atDay(1).atStartOfDay();
		LocalDateTime until = month.plusMonths(1).atDay(1).atStartOfDay();
		LocalDateTime coveredFrom = repository.findCoveredFrom();
		if (coveredFrom == null || !coveredFrom.isBefore(until)) {
			throw new CustomException(MonthlyReportErrorCode.SOURCE_HISTORY_UNAVAILABLE);
		}
		List<RecordState> records = new ArrayList<>();
		Set<Long> saved = new HashSet<>();
		Set<Long> planned = new HashSet<>();
		Set<Long> courseIds = new HashSet<>();
		long courses = 0;
		for (MonthlyReportSourceHistory history : repository.findOwnerState(memberId, until)) {
			switch (history.getSourceType()) {
				case BREAD_RECORD -> {
					RecordState record = decode(history, RecordState.class);
					if (record.createdAt().isBefore(until) && retained(record.deletedAt(), until)
						&& inMonth(record.eatenAt(), from, until)) {
						records.add(record);
					}
				}
				case SAVED_STORE, STORE_PLAN -> {
					RelationState relation = decode(history, RelationState.class);
					if (inMonth(relation.createdAt(), from, until)) {
						switch (history.getSourceType()) {
							case SAVED_STORE -> saved.add(relation.storeId());
							case STORE_PLAN -> planned.add(relation.storeId());
							default -> throw new IllegalStateException();
						}
					}
				}
				case COURSE -> {
					CourseState course = decode(history, CourseState.class);
					if (course.createdAt().isBefore(until) && retained(course.deletedAt(), until)) {
						courseIds.add(course.id());
						if (inMonth(course.createdAt(), from, until)) {
							courses++;
						}
					}
				}
				default -> throw new CustomException(MonthlyReportErrorCode.SOURCE_HISTORY_UNAVAILABLE);
			}
		}
		for (MonthlyReportSourceHistory history : children("COURSE_STORE", courseIds, until)) {
			RelationState relation = decode(history, RelationState.class);
			if (inMonth(relation.createdAt(), from, until) && retained(relation.deletedAt(), until)) {
				planned.add(relation.storeId());
			}
		}
		Set<Long> recordIds = records.stream().map(RecordState::id).collect(Collectors.toSet());
		Map<Long, PhotoState> photos = new HashMap<>();
		for (MonthlyReportSourceHistory history : children("BREAD_PHOTO", recordIds, until)) {
			PhotoState photo = decode(history, PhotoState.class);
			photos.put(photo.id(), photo);
		}
		List<TagState> tags = children("BREAD_TAG", recordIds, until).stream()
			.map(history -> decode(history, TagState.class)).sorted(Comparator.comparing(TagState::id)).toList();
		records.sort(Comparator.comparing(RecordState::id));
		return new MonthlyReportSourceSnapshot(coveredFrom, records, tags, photos, saved, planned, courses);
	}

	private List<MonthlyReportSourceHistory> children(String sourceType, Set<Long> parentIds, LocalDateTime until) {
		return parentIds.isEmpty() ? List.of() : repository.findChildState(sourceType, parentIds, until);
	}

	private boolean inMonth(LocalDateTime time, LocalDateTime from, LocalDateTime until) {
		return !time.isBefore(from) && time.isBefore(until);
	}

	private boolean retained(LocalDateTime deletedAt, LocalDateTime until) {
		return deletedAt == null || !deletedAt.isBefore(until);
	}

	private <T> T decode(MonthlyReportSourceHistory history, Class<T> type) {
		try {
			return mapper.readValue(history.getPayload(), type);
		} catch (JsonProcessingException | IllegalArgumentException e) {
			throw new CustomException(MonthlyReportErrorCode.SOURCE_HISTORY_UNAVAILABLE, e);
		}
	}
}
