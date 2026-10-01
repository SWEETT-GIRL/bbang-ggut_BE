package sweetgirl.croissant.monthlyreport.service;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportListItem;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportPhotoSource;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository.CurrentPhoto;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportQueryService {

	private final MemberReader memberReader;
	private final MonthlyReportRepository repository;
	private final MonthlyReportPayloadCodec codec;

	public SliceResponse<MonthlyReportListItem> getReports(Long memberId, int page, int size) {
		memberReader.getMember(memberId);
		if (page < 0 || size < 1 || size > 100) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_PAGE);
		}
		var reports = repository.findPublished(memberId, PageRequest.of(page, size));
		return SliceResponse.from(reports.map(report -> MonthlyReportListItem.of(
			report.getYearMonth(), report.getPublishedAt(), new MonthlyReportResponse.Character(
				report.getCharacterCode(), report.getCharacterName(), report.getCharacterImageKey()))));
	}

	public MonthlyReportResponse getReport(Long memberId, String yearMonth) {
		var member = memberReader.getMember(memberId);
		MonthlyReportMonth.parse(yearMonth);
		var snapshot = repository.findById(new MonthlyReportMemberId(memberId, yearMonth))
			.orElseThrow(() -> new CustomException(MonthlyReportErrorCode.NOT_PUBLISHED));
		switch (snapshot.getStatus()) {
			case WITHHELD -> throw new CustomException(MonthlyReportErrorCode.NOT_PUBLISHED);
			case FAILED -> throw new CustomException(MonthlyReportErrorCode.GENERATION_FAILED);
			case PUBLISHED -> {
				MonthlyReportResponse report = codec.read(snapshot.getPayload(), memberId, yearMonth);
				if (!Objects.equals(snapshot.getCharacterCode(), report.character().code())
					|| !Objects.equals(snapshot.getCharacterName(), report.character().name())
					|| !Objects.equals(snapshot.getCharacterImageKey(), report.character().imageKey())
					|| !Objects.equals(snapshot.getPlanetCode(), report.planet().code())
					|| !Objects.equals(snapshot.getPlanetName(), report.planet().name())) {
					throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
				}
				return resolveCurrentPhotos(report, codec.readPhotoSources(snapshot.getRawMetrics()))
					.withNickname(member.getNickname());
			}
			default -> throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
		}
	}

	private MonthlyReportResponse resolveCurrentPhotos(MonthlyReportResponse report,
			Map<String, MonthlyReportPhotoSource> sources) {
		Set<UUID> references = sources.values().stream().map(MonthlyReportPhotoSource::photoReferenceId)
			.collect(Collectors.toSet());
		Map<UUID, CurrentPhoto> current = references.isEmpty() ? Map.of()
			: repository.findCurrentPhotos(report.memberId(), references).stream()
				.collect(Collectors.toMap(CurrentPhoto::getReferenceId, photo -> photo));
		Set<String> keys = report.breads().stream().filter(bread -> !sources.containsKey(bread.name()))
			.map(MonthlyReportResponse.Bread::imageKey)
			.filter(Objects::nonNull).collect(Collectors.toSet());
		Set<String> visible = keys.isEmpty() ? Set.of() : repository.findVisiblePhotoKeys(report.memberId(), keys);
		return report.withBreads(report.breads().stream()
			.map(bread -> {
				var source = sources.get(bread.name());
				String key = source == null
					? (bread.imageKey() != null && visible.contains(bread.imageKey()) ? bread.imageKey() : null)
					: currentImageKey(source, current.get(source.photoReferenceId()));
				return new MonthlyReportResponse.Bread(bread.name(), bread.count(), key);
			})
			.toList());
	}

	private String currentImageKey(MonthlyReportPhotoSource source, CurrentPhoto photo) {
		if (photo == null || !source.recordId().equals(photo.getRecordId())) {
			return null;
		}
		return Stream
			.of(photo.getResizedKey640(), photo.getResizedKey320(), photo.getResizedKey1024(), photo.getAwsKey())
			.filter(key -> key != null && !key.isBlank()).findFirst().orElse(null);
	}
}
