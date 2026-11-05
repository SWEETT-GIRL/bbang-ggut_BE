package sweetgirl.croissant.profile.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.service.ReportedTargetReader;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.follow.service.FollowVisibilityReader;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.SocialMemberReader;
import sweetgirl.croissant.profile.code.ProfileErrorCode;
import sweetgirl.croissant.profile.dto.response.VisitedStoreResponse;
import sweetgirl.croissant.profile.exception.ProfileException;
import sweetgirl.croissant.store.entity.Store;

/**
 * 다녀간 빵집 지도(칭호를 누르면 여는 화면). 그 사람의 공개 빵로그가 있는 가게를 핀으로 — 보는 사람에게 보이는 기록만 센다
 * (차단 관계면 403, 내가 신고한 기록은 뺀다). 칭호와 같은 재료(공개 · 숨김 아님 · 가게를 고른 기록).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VisitedStoreQueryService {

	static final int TOP_BREADS = 2;

	private final BreadRecordRepository breadRecordRepository;
	private final BreadRecordPhotoRepository photoRepository;
	private final BreadRecordTagRepository tagRepository;
	private final SocialMemberReader socialMemberReader;
	private final MemberBlockRepository memberBlockRepository;
	private final FollowVisibilityReader followVisibilityReader;
	private final ReportedTargetReader reportedTargetReader;

	/**
	 * 상대가 게스트 · 봇 · 탈퇴면 404 PROFILE404_MEMBER, 차단 관계면 403 PROFILE403_BLOCKED — 프로필과 같은 규칙.
	 * 상대가 비공개 계정이고 내가 팔로워가 아니면 빈 목록.
	 */
	public List<VisitedStoreResponse> getVisitedStores(Long viewerId, Long memberId) {
		Member owner = socialMemberReader.findSocial(memberId)
			.orElseThrow(() -> new ProfileException(ProfileErrorCode.PROFILE_MEMBER_NOT_FOUND));
		if (viewerId != null && !viewerId.equals(memberId) && memberBlockRepository.existsBetween(viewerId, memberId)) {
			throw new ProfileException(ProfileErrorCode.PROFILE_BLOCKED);
		}
		if (!followVisibilityReader.canViewRecords(viewerId, owner)) {
			return List.of();
		}
		Set<Long> reported = reportedTargetReader.reportedIds(viewerId, AbuseReportTargetType.RECORD);
		List<BreadRecord> records = breadRecordRepository.findPublicWithStoreByMemberId(memberId).stream()
			.filter(record -> !reported.contains(record.getId()))
			.toList();
		if (records.isEmpty()) {
			return List.of();
		}
		// 최신순으로 받았으니 가게마다 처음 만나는 기록이 가장 최근 기록이다
		Map<Long, List<BreadRecord>> byStore = new LinkedHashMap<>();
		records
			.forEach(record -> byStore.computeIfAbsent(record.getStore().getId(), id -> new ArrayList<>()).add(record));
		Map<Long, String> thumbnailByRecord = firstPhotoKeys(
			byStore.values().stream().map(group -> group.get(0).getId()).toList());
		Map<Long, List<String>> tagNamesByRecord = tagNames(records.stream().map(BreadRecord::getId).toList());

		return byStore.values().stream()
			.map(group -> toResponse(group, thumbnailByRecord, tagNamesByRecord))
			.sorted(Comparator.comparingLong(VisitedStoreResponse::publicRecordCount).reversed())
			.toList();
	}

	private VisitedStoreResponse toResponse(List<BreadRecord> group, Map<Long, String> thumbnailByRecord,
			Map<Long, List<String>> tagNamesByRecord) {
		BreadRecord latest = group.get(0);
		Store store = latest.getStore();
		Map<String, Integer> breadCounts = new HashMap<>();
		group.forEach(record -> tagNamesByRecord.getOrDefault(record.getId(), List.of())
			.forEach(name -> breadCounts.merge(name, 1, Integer::sum)));
		List<String> topBreads = breadCounts.entrySet().stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
			.limit(TOP_BREADS)
			.map(Map.Entry::getKey)
			.toList();
		return new VisitedStoreResponse(
			store.getId(),
			store.getName(),
			store.getLocation() == null ? null : store.getLocation().getY(),
			store.getLocation() == null ? null : store.getLocation().getX(),
			group.size(),
			thumbnailByRecord.get(latest.getId()),
			topBreads);
	}

	/** 기록마다 첫 사진의 리사이즈 320 키(없으면 원본 키). */
	private Map<Long, String> firstPhotoKeys(List<Long> recordIds) {
		Map<Long, String> keys = new HashMap<>();
		for (BreadRecordPhoto photo : photoRepository.findByRecordIdInOrderByRecordIdAscSortOrderAscIdAsc(recordIds)) {
			keys.putIfAbsent(photo.getRecord().getId(),
				photo.getResizedKey320() != null ? photo.getResizedKey320() : photo.getAwsKey());
		}
		return keys;
	}

	private Map<Long, List<String>> tagNames(List<Long> recordIds) {
		Map<Long, List<String>> names = new HashMap<>();
		for (BreadRecordTag tag : tagRepository.findByRecordIdInWithPhoto(recordIds)) {
			names.computeIfAbsent(tag.getRecord().getId(), id -> new ArrayList<>()).add(tag.getName());
		}
		return names;
	}
}
