package sweetgirl.croissant.member.service;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.dto.response.BlockListResponse;
import sweetgirl.croissant.member.dto.response.BlockResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.MemberBlock;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.profile.dto.response.CharacterFace;
import sweetgirl.croissant.profile.service.CharacterFaceReader;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberBlockQueryService {

	private final MemberReader memberReader;
	private final MemberBlockRepository memberBlockRepository;
	private final CharacterFaceReader characterFaceReader;

	public BlockListResponse getBlocks(Long memberId, int page, int size) {
		if (page < 0 || size < 1) {
			throw new IllegalArgumentException("page and size must be valid");
		}
		Member blocker = memberReader.getMember(memberId);

		// size+1 로 page 를 넘기면 offset 이 page×(size+1) 로 밀려 쪽마다 page 개 행이 빠졌다 — Slice 가 다음 쪽 여부를 센다
		Slice<MemberBlock> slice = memberBlockRepository.findAllByBlockerIdOrderByCreatedAtDescIdDesc(
			blocker.getId(),
			PageRequest.of(page, size));
		boolean hasNext = slice.hasNext();
		List<MemberBlock> pageBlocks = slice.getContent();

		Map<Long, CharacterFace> faces = characterFaceReader.facesOf(
			pageBlocks.stream().map(block -> block.getBlockedMember().getId()).toList());
		return new BlockListResponse(
			pageBlocks.stream()
				.map(block -> BlockResponse.from(block, faces.get(block.getBlockedMember().getId())))
				.toList(),
			hasNext);
	}
}
