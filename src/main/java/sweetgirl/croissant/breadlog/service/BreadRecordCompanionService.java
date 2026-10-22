package sweetgirl.croissant.breadlog.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.service.CommunityNotificationService;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.response.CompanionResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordCompanion;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordCompanionRepository;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.SocialMemberReader;
import sweetgirl.croissant.profile.dto.response.CharacterFace;
import sweetgirl.croissant.profile.service.CharacterFaceReader;

/**
 * 빵로그의 같이 간 사람. 작성자가 쓰기 · 고치기에서 통째로 넘기고({@link #replace}), 태그된 사람은 스스로 뺀다({@link #removeBySelf}).
 *
 * <p>태그할 수 있는 사람은 팔로우 관계(어느 쪽이든)이거나 같은 빵투어의 일행뿐이고, 작성자가 하루에 태그할 수 있는 사람은
 * {@value #DAILY_TAG_LIMIT} 명(KST, 기록을 통틀어 중복 없이)이다. 작성 제한 · 게스트는 부르는 쪽(기록 쓰기)이 먼저 막는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class BreadRecordCompanionService {

	static final int DAILY_TAG_LIMIT = 5;
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final BreadRecordCompanionRepository companionRepository;
	private final MemberFollowRepository memberFollowRepository;
	private final CourseMemberRepository courseMemberRepository;
	private final MemberBlockRepository memberBlockRepository;
	private final SocialMemberReader socialMemberReader;
	private final MemberReader memberReader;
	private final CharacterFaceReader characterFaceReader;
	private final CommunityNotificationService communityNotificationService;

	/**
	 * 요청한 사람들로 맞춘다. {@code requestedIds} 가 null 이면(구버전 앱) 손대지 않는다.
	 * 새로 들어온 사람은 검사하고 알리고, 빠진 사람은 행을 지운다(스스로 뺀 사람의 행은 남긴다 — 다시 넣을 수 없게).
	 */
	public void replace(BreadRecord record, List<Long> requestedIds) {
		if (requestedIds == null) {
			return;
		}
		Long ownerId = record.getMember().getId();
		Set<Long> requested = new LinkedHashSet<>(requestedIds);
		if (!requested.isEmpty()) {
			// 태그는 남에게 닿는 소셜 행동 — 게스트는 스위치(guest.write-blocked)와 무관하게 막는다
			memberReader.requireLoggedIn(ownerId);
		}
		if (requested.contains(ownerId)) {
			throw new BreadLogException(BreadLogErrorCode.COMPANION_SELF);
		}
		Map<Long, BreadRecordCompanion> existingByMember = companionRepository.findAllByIdRecordId(record.getId())
			.stream()
			.collect(Collectors.toMap(companion -> companion.getId().getMemberId(), Function.identity()));

		List<Member> added = new ArrayList<>();
		for (Long memberId : requested) {
			BreadRecordCompanion existing = existingByMember.get(memberId);
			if (existing != null) {
				if (existing.isRemoved()) {
					throw new BreadLogException(BreadLogErrorCode.COMPANION_REMOVED);
				}
				continue;
			}
			added.add(validateTaggable(ownerId, memberId));
		}
		validateDailyLimit(ownerId, added);

		existingByMember.values().stream()
			.filter(companion -> !companion.isRemoved() && !requested.contains(companion.getId().getMemberId()))
			.forEach(companionRepository::delete);
		LocalDateTime now = LocalDateTime.now(KST);
		companionRepository
			.saveAll(added.stream().map(member -> BreadRecordCompanion.of(record, member, now)).toList());
		communityNotificationService.notifyCompanionTag(record, added);
	}

	/** 상대가 없거나 게스트 · 봇 · 탈퇴면 404, 차단 관계면 403, 팔로우도 일행도 아니면 400. */
	private Member validateTaggable(Long ownerId, Long memberId) {
		Member member = socialMemberReader.findSocial(memberId)
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.COMPANION_MEMBER_NOT_FOUND));
		if (memberBlockRepository.existsBetween(ownerId, memberId)) {
			throw new BreadLogException(BreadLogErrorCode.COMPANION_BLOCKED);
		}
		boolean related = memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(ownerId, memberId)
			|| memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(memberId, ownerId)
			|| courseMemberRepository.existsSharedActiveCourse(ownerId, memberId);
		if (!related) {
			throw new BreadLogException(BreadLogErrorCode.COMPANION_NOT_RELATED);
		}
		return member;
	}

	/** 오늘 이미 태그한 사람과 새로 태그할 사람을 합쳐 {@value #DAILY_TAG_LIMIT} 명을 넘기면 429. */
	private void validateDailyLimit(Long ownerId, List<Member> added) {
		if (added.isEmpty()) {
			return;
		}
		Set<Long> taggedToday = new HashSet<>(
			companionRepository.findMemberIdsTaggedSince(ownerId, LocalDate.now(KST).atStartOfDay()));
		added.forEach(member -> taggedToday.add(member.getId()));
		if (taggedToday.size() > DAILY_TAG_LIMIT) {
			throw new BreadLogException(BreadLogErrorCode.COMPANION_DAILY_LIMIT);
		}
	}

	/** 태그된 사람이 스스로 뺀다 — 알림 없음, 작성자 화면에서도 빠진다. 태그된 적 없으면 404, 이미 뺐으면 그대로 200. */
	public void removeBySelf(Long recordId, Long memberId) {
		BreadRecordCompanion companion = companionRepository.findByIdRecordIdAndIdMemberId(recordId, memberId)
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.COMPANION_NOT_TAGGED));
		companion.removeBySelf(LocalDateTime.now(KST));
	}

	/** 상세에 보이는 같이 간 사람(얼굴 포함). */
	@Transactional(readOnly = true)
	public List<CompanionResponse> companionsOf(Long recordId) {
		List<BreadRecordCompanion> companions = companionRepository.findActiveByRecordId(recordId);
		Map<Long, CharacterFace> faces = characterFaceReader.facesOf(
			companions.stream().map(companion -> companion.getMember().getId()).toList());
		return companions.stream()
			.map(companion -> CompanionResponse.of(companion, faces.get(companion.getMember().getId())))
			.toList();
	}
}
