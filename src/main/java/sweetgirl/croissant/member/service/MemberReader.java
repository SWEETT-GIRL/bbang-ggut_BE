package sweetgirl.croissant.member.service;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;

@Component
@RequiredArgsConstructor
public class MemberReader {

	private final MemberRepository memberRepository;

	public Member getMember(Long memberId) {
		// TODO: 인증 완료 후 아래 폴백 제거 — SecurityContext에서 memberId 주입
		Long resolvedId = memberId != null ? memberId : 1L;
		Member member = memberRepository.findById(resolvedId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
		if (member.getDeletedAt() != null) {
			throw new MemberException(MemberErrorCode.MEMBER_DELETED);
		}
		return member;
	}
}
