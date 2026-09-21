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
		if (memberId == null) {
			throw new MemberException(MemberErrorCode.UNAUTHORIZED);
		}
		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
		if (member.getDeletedAt() != null) {
			throw new MemberException(MemberErrorCode.MEMBER_DELETED);
		}
		return member;
	}

	/**
	 * 로그인한 회원인가. 게스트면 막는다.
	 *
	 * <p>제재 검사({@code validateCanWrite})와 따로 두는 이유: 규칙이 다르다. 쓰기 제한 회원은
	 * 남에게 보이는 글만 못 쓰고 자기 메모를 지우는 건 되지만, 게스트는 저장·기록·만들기 자체를 못 한다.
	 *
	 * <p><b>401 이 아니라 403 이다.</b> 앱 apiClient 는 401 을 받으면 게스트 세션을 자동
	 * 재발급하므로, 401 로 막으면 같은 요청이 끝없이 되풀이된다.
	 */
	public void requireLoggedIn(Long memberId) {
		if (getMember(memberId).isGuest()) {
			throw new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED);
		}
	}
}
