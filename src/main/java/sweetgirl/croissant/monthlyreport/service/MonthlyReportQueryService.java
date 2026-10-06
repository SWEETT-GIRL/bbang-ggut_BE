package sweetgirl.croissant.monthlyreport.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportListItem;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportPhotoSource;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMember;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository.PublishedReport;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportQueryService {

	private final MemberReader memberReader;
	private final MonthlyReportRepository repository;
	private final MonthlyReportPayloadCodec codec;
	private final BreadRecordTagRepository tagRepository;

	public SliceResponse<MonthlyReportListItem> getReports(Long memberId, int page, int size) {
		memberReader.getMember(memberId);
		if (page < 0 || size < 1 || size > 100) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_PAGE);
		}
		Slice<PublishedReport> reports = repository.findPublished(memberId, PageRequest.of(page, size));
		return SliceResponse.from(reports.map(report -> MonthlyReportListItem.of(
			report.getYearMonth(), report.getPublishedAt(), new MonthlyReportResponse.Character(
				report.getCharacterCode(), report.getCharacterName(), report.getCharacterImageKey(),
				report.getFaceImageKey()))));
	}

	public MonthlyReportResponse getReport(Long memberId, String yearMonth) {
		Member member = memberReader.getMember(memberId);
		MonthlyReportMonth.parse(yearMonth);
		MonthlyReportMember snapshot = repository.findById(new MonthlyReportMemberId(memberId, yearMonth))
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

	public MonthlyReportResponse resolveCurrentPhotos(MonthlyReportResponse report,
			Map<String, MonthlyReportPhotoSource> sources) {
		Set<UUID> references = sources.values().stream().map(MonthlyReportPhotoSource::photoReferenceId)
			.collect(Collectors.toSet());
		Set<String> keys = report.breads().stream().filter(bread -> !sources.containsKey(bread.name()))
			.map(MonthlyReportResponse.Bread::imageKey)
			.filter(Objects::nonNull).collect(Collectors.toSet());
		List<BreadRecordTag> tags = references.isEmpty() && keys.isEmpty() ? List.of()
			: tagRepository.findMonthlyReportPhotoTags(report.memberId(), references, keys);
		Map<String, Map<MonthlyReportPhotoSource, BreadRecordPhoto>> current = new HashMap<>();
		for (BreadRecordTag tag : tags) {
			if (tag.uncategorized()) {
				continue;
			}
			BreadRecordPhoto photo = tag.getPhoto();
			MonthlyReportPhotoSource source = new MonthlyReportPhotoSource(tag.getRecord().getId(),
				photo.getReferenceId());
			current.computeIfAbsent(tag.resolvedCategory(), category -> new HashMap<>()).put(source, photo);
		}
		return report.withBreads(report.breads().stream()
			.map(bread -> {
				MonthlyReportPhotoSource source = sources.get(bread.name());
				Map<MonthlyReportPhotoSource, BreadRecordPhoto> candidates = current.getOrDefault(bread.name(),
					Map.of());
				String key = source == null
					? legacyImageKey(bread.imageKey(), candidates)
					: currentImageKey(candidates.get(source));
				return new MonthlyReportResponse.Bread(bread.name(), bread.count(), key);
			})
			.toList());
	}

	private String currentImageKey(BreadRecordPhoto photo) {
		if (photo == null) {
			return null;
		}
		return Stream
			.of(photo.getResizedKey640(), photo.getResizedKey320(), photo.getResizedKey1024(), photo.getAwsKey())
			.filter(key -> key != null && !key.isBlank()).findFirst().orElse(null);
	}

	private String legacyImageKey(String key, Map<MonthlyReportPhotoSource, BreadRecordPhoto> candidates) {
		if (key == null) {
			return null;
		}
		boolean visible = candidates.values().stream().anyMatch(photo -> Stream
			.of(photo.getAwsKey(), photo.getResizedKey320(), photo.getResizedKey640(), photo.getResizedKey1024())
			.anyMatch(key::equals));
		return visible ? key : null;
	}
}
