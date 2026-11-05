package sweetgirl.croissant.profile.service;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.profile.dto.response.AuthorCardResponse;
import sweetgirl.croissant.world.entity.WorldCharacter;
import sweetgirl.croissant.world.entity.WorldCharacterAssetId;
import sweetgirl.croissant.world.repository.MemberWorldRepository;
import sweetgirl.croissant.world.repository.WorldCharacterAssetRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;

/** 글쓴이 줄. 프로필과 같은 재료(캐릭터 얼굴 · 공개 빵로그 수 · 칭호 · 팔로우 상태)를 한 사람 단위로 읽는다. */
@Component
@RequiredArgsConstructor
public class AuthorCardReader {

	private static final String FACE_POSE = "front";

	private final MemberWorldRepository memberWorldRepository;
	private final WorldCharacterRepository worldCharacterRepository;
	private final WorldCharacterAssetRepository worldCharacterAssetRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final MemberTitleReader memberTitleReader;
	private final MemberFollowRepository memberFollowRepository;

	public AuthorCardResponse read(Long viewerId, Member author) {
		Long authorId = author.getId();
		boolean isMe = authorId.equals(viewerId);
		WorldCharacter character = memberWorldRepository.findById(authorId)
			.flatMap(world -> worldCharacterRepository.findById(world.getSelectedCharacter()))
			.or(worldCharacterRepository::findByMascotTrue)
			.orElse(null);
		String characterCode = character == null ? null : character.getCode();
		String characterImageKey = character == null ? null
			: worldCharacterAssetRepository.findById(new WorldCharacterAssetId(character.getCode(), FACE_POSE))
				.map(asset -> asset.getImageKey()).orElse(null);
		return new AuthorCardResponse(
			authorId, author.getNickname(), characterCode, characterImageKey,
			breadRecordRepository.countPublicByMemberId(authorId),
			memberTitleReader.titleOf(authorId).orElse(null),
			isMe,
			viewerId != null && !isMe && memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(viewerId, authorId),
			viewerId != null && !isMe
				&& memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(authorId, viewerId));
	}
}
