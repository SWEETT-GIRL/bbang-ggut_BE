package sweetgirl.croissant.profile.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.profile.dto.response.CharacterFace;
import sweetgirl.croissant.world.entity.MemberWorld;
import sweetgirl.croissant.world.entity.WorldCharacterAssetId;
import sweetgirl.croissant.world.repository.MemberWorldRepository;
import sweetgirl.croissant.world.repository.WorldCharacterAssetRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;

/** 여러 사람의 얼굴을 한 번에 — 댓글 목록 · 좋아요 목록 줄마다 쿼리하지 않는다. */
@Component
@RequiredArgsConstructor
public class CharacterFaceReader {

	private static final String FACE_POSE = "front";

	private final MemberWorldRepository memberWorldRepository;
	private final WorldCharacterRepository worldCharacterRepository;
	private final WorldCharacterAssetRepository worldCharacterAssetRepository;

	public Map<Long, CharacterFace> facesOf(Collection<Long> memberIds) {
		if (memberIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, String> codeByMember = memberWorldRepository.findAllById(memberIds).stream()
			.collect(Collectors.toMap(MemberWorld::getMemberId, MemberWorld::getSelectedCharacter));
		String mascot = worldCharacterRepository.findByMascotTrue().map(character -> character.getCode()).orElse(null);
		Map<String, CharacterFace> faceByCode = new HashMap<>();
		Map<Long, CharacterFace> faces = new HashMap<>();
		for (Long memberId : memberIds) {
			String code = codeByMember.getOrDefault(memberId, mascot);
			if (code == null) {
				faces.put(memberId, CharacterFace.NONE);
				continue;
			}
			faces.put(memberId, faceByCode.computeIfAbsent(code, this::faceOf));
		}
		return faces;
	}

	private CharacterFace faceOf(String code) {
		return new CharacterFace(code,
			worldCharacterAssetRepository.findById(new WorldCharacterAssetId(code, FACE_POSE))
				.map(asset -> asset.getImageKey()).orElse(null));
	}

	public CharacterFace faceOf(Long memberId) {
		return Optional.ofNullable(facesOf(java.util.List.of(memberId)).get(memberId)).orElse(CharacterFace.NONE);
	}

	static <T> Function<T, T> identity() {
		return Function.identity();
	}
}
