package sweetgirl.croissant.profile.service;

import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.service.ReportedTargetReader;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.follow.repository.MemberFollowRequestRepository;
import sweetgirl.croissant.follow.service.FollowVisibilityReader;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.member.service.SocialMemberReader;
import sweetgirl.croissant.profile.code.ProfileErrorCode;
import sweetgirl.croissant.profile.dto.response.ProfileResponse;
import sweetgirl.croissant.profile.exception.ProfileException;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.entity.WorldCharacter;
import sweetgirl.croissant.world.entity.WorldCharacterAssetId;
import sweetgirl.croissant.world.repository.MemberWorldRepository;
import sweetgirl.croissant.world.repository.WorldCharacterAssetRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.service.WorldCatalogService;
import sweetgirl.croissant.world.service.WorldLevel;
import sweetgirl.croissant.world.service.WorldLevelReader;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileQueryService {

	private static final String FACE_POSE = "front";

	private final SocialMemberReader socialMemberReader;
	private final MemberBlockRepository memberBlockRepository;
	private final MemberRestrictionReader memberRestrictionReader;
	private final MemberFollowRepository memberFollowRepository;
	private final MemberFollowRequestRepository memberFollowRequestRepository;
	private final FollowVisibilityReader followVisibilityReader;
	private final BreadRecordRepository breadRecordRepository;
	private final MemberTitleReader memberTitleReader;
	private final MemberWorldRepository memberWorldRepository;
	private final WorldCharacterRepository worldCharacterRepository;
	private final WorldCharacterAssetRepository worldCharacterAssetRepository;
	private final WorldLevelReader worldLevelReader;
	private final WorldCatalogService worldCatalogService;
	private final ReportedTargetReader reportedTargetReader;

	/**
	 * 보는 사람({@code viewerId})은 게스트여도 되고 비로그인이면 null. 상대가 게스트 · 봇 · 탈퇴면 404,
	 * 차단 관계거나 프로필 제한 중이면(내 프로필은 예외) {@code blocked: true} 로 200.
	 * 비공개 계정도 프로필(닉네임 · 캐릭터 · 수)은 보인다 — 빵로그만 팔로워에게 열린다({@code canViewRecords}).
	 */
	public ProfileResponse getProfile(Long viewerId, Long memberId) {
		Member member = socialMemberReader.findSocial(memberId)
			.orElseThrow(() -> new ProfileException(ProfileErrorCode.PROFILE_MEMBER_NOT_FOUND));
		boolean isMe = memberId.equals(viewerId);
		if (!isMe && (viewerId != null && memberBlockRepository.existsBetween(viewerId, memberId)
			|| memberRestrictionReader.isProfileRestricted(memberId)
			|| reportedTargetReader.hasReported(viewerId, AbuseReportTargetType.MEMBER_PROFILE, memberId))) {
			return ProfileResponse.blocked(memberId);
		}
		WorldCharacter character = memberWorldRepository.findById(memberId)
			.flatMap(world -> worldCharacterRepository.findById(world.getSelectedCharacter()))
			.or(worldCharacterRepository::findByMascotTrue)
			.orElse(null);
		String characterCode = character == null ? null : character.getCode();
		String characterImageKey = character == null ? null
			: worldCharacterAssetRepository.findById(new WorldCharacterAssetId(character.getCode(), FACE_POSE))
				.map(asset -> asset.getImageKey()).orElse(null);
		WorldLevel level = worldLevelReader.currentLevel(memberId);
		boolean followedByMe = viewerId != null && !isMe
			&& memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(viewerId, memberId);
		boolean followsMe = viewerId != null && !isMe
			&& memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(memberId, viewerId);
		boolean followRequestedByMe = viewerId != null && !isMe && member.isPrivate() && !followedByMe
			&& memberFollowRequestRepository.existsByIdRequesterIdAndIdTargetId(viewerId, memberId);
		return new ProfileResponse(
			memberId, false, isMe, member.getNickname(),
			characterCode, characterImageKey,
			memberWorldRepository.findHomePropItem(memberId).orElse(null),
			level.level(), level.planet(), planetNameOf(level.planet()),
			memberTitleReader.titleOf(memberId).orElse(null),
			visiblePublicRecordCount(viewerId, memberId),
			memberFollowRepository.countByIdFolloweeId(memberId),
			memberFollowRepository.countByIdFollowerId(memberId),
			followedByMe, followsMe,
			member.isPrivate(), followRequestedByMe, followVisibilityReader.canViewRecords(viewerId, member));
	}

	/** 보는 사람이 신고한 빵로그는 목록에서 빠지므로 수에서도 뺀다. */
	private long visiblePublicRecordCount(Long viewerId, Long memberId) {
		Set<Long> reported = reportedTargetReader.reportedIds(viewerId, AbuseReportTargetType.RECORD);
		return reported.isEmpty() ? breadRecordRepository.countPublicByMemberId(memberId)
			: breadRecordRepository.countPublicByMemberIdExcluding(memberId, reported);
	}

	private String planetNameOf(String planetCode) {
		if (planetCode == null) {
			return null;
		}
		return worldCatalogService.getCatalog().planets().stream()
			.filter(planet -> planet.code().equals(planetCode))
			.map(WorldPlanetResponse::name)
			.findFirst()
			.orElse(null);
	}
}
