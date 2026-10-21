package sweetgirl.croissant.member.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.dto.request.BlockCreateRequest;
import sweetgirl.croissant.member.dto.response.BlockResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.MemberBlock;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.repository.MemberRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberBlockCommandService {

	private final MemberReader memberReader;
	private final MemberRepository memberRepository;
	private final MemberBlockRepository memberBlockRepository;

	public BlockResponse blockMember(Long memberId, BlockCreateRequest request) {
		Member blocker = memberReader.getMember(memberId);
		Long blockedMemberId = request.blockedMemberId();
		if (blocker.getId().equals(blockedMemberId)) {
			throw new MemberException(MemberErrorCode.MEMBER_BLOCK_SELF_NOT_ALLOWED);
		}

		Member blockedMember = memberRepository.findByIdAndDeletedAtIsNull(blockedMemberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_BLOCK_TARGET_NOT_FOUND));

		return memberBlockRepository.findByBlockerIdAndBlockedMemberId(blocker.getId(), blockedMember.getId())
			.map(BlockResponse::from)
			.orElseGet(() -> {
				MemberBlock memberBlock = memberBlockRepository.save(MemberBlock.builder()
					.blocker(blocker)
					.blockedMember(blockedMember)
					.build());
				return BlockResponse.from(memberBlock);
			});
	}

	public void unblockMember(Long memberId, Long blockedMemberId) {
		Member blocker = memberReader.getMember(memberId);
		memberBlockRepository.deleteByBlockerIdAndBlockedMemberId(blocker.getId(), blockedMemberId);
	}
}
