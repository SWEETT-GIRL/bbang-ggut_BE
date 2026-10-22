package sweetgirl.croissant.member.service;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
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

		List<MemberBlock> memberBlocks = memberBlockRepository.findAllByBlockerIdOrderByCreatedAtDescIdDesc(
			blocker.getId(),
			PageRequest.of(page, size + 1));
		boolean hasNext = memberBlocks.size() > size;
		List<MemberBlock> pageBlocks = hasNext ? memberBlocks.subList(0, size) : memberBlocks;

		Map<Long, CharacterFace> faces = characterFaceReader.facesOf(
			pageBlocks.stream().map(block -> block.getBlockedMember().getId()).toList());
		return new BlockListResponse(
			pageBlocks.stream()
				.map(block -> BlockResponse.from(block, faces.get(block.getBlockedMember().getId())))
				.toList(),
			hasNext);
	}
}
