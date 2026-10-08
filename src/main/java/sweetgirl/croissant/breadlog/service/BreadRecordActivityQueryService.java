package sweetgirl.croissant.breadlog.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.projection.BreadRecordActivityProjection;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordActivityResponse;
import sweetgirl.croissant.breadlog.enums.RecordType;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.member.service.MemberReader;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BreadRecordActivityQueryService {

	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	private final MemberReader memberReader;
	private final BreadRecordRepository repository;
	private final Clock clock;

	public BreadRecordActivityResponse getActivity(Long memberId) {
		memberReader.getMember(memberId);
		OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZONE);
		List<BreadRecordActivityProjection> records = repository
			.findAllByMemberIdAndDeletedAtIsNullAndCreatedAtLessThanEqual(memberId, now.toLocalDateTime());
		return summarize(records, now);
	}

	private BreadRecordActivityResponse summarize(List<BreadRecordActivityProjection> records, OffsetDateTime now) {
		LocalDate today = now.toLocalDate();
		Set<LocalDate> days = new HashSet<>();
		records.forEach(record -> days.add(record.getCreatedAt().toLocalDate()));
		boolean recordedToday = days.contains(today);
		int streak = countStreak(days, recordedToday ? today : today.minusDays(1));
		var normalRecords = records.stream().filter(record -> record.getRecordType() == RecordType.NORMAL).toList();
		var latestNormal = normalRecords.stream().max(Comparator
			.comparing(BreadRecordActivityProjection::getCreatedAt)
			.thenComparing(BreadRecordActivityProjection::getId));
		return new BreadRecordActivityResponse(today, ZONE.getId(), now, recordedToday, streak,
			recordedToday ? streak : streak + 1, records.size(), normalRecords.size(),
			days.stream().max(Comparator.naturalOrder()).orElse(null),
			latestNormal.map(BreadRecordActivityProjection::getId).orElse(null),
			latestNormal.map(record -> record.getEatenAt().toLocalDate()).orElse(null),
			BreadRecordActivityResponse.POLICY_VERSION);
	}

	private int countStreak(Set<LocalDate> days, LocalDate anchor) {
		int streak = 0;
		while (days.contains(anchor.minusDays(streak))) {
			streak++;
		}
		return streak;
	}
}
