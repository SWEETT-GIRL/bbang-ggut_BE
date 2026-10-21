package sweetgirl.croissant.member.service;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.code.NicknameErrorCode;
import sweetgirl.croissant.member.exception.NicknameException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.util.NicknameNormalizer;
import sweetgirl.croissant.moderation.service.ForbiddenWordFilter;

/**
 * 새 닉네임 규칙 — 한글 · 영문 · 숫자 2~10자, 공백 없음, 금칙어 없음, 살아 있는 회원끼리 유일(정규화 비교).
 *
 * <p>자유 입력과 뽑기 수락이 같이 쓴다. 기존 닉네임(띄어쓰기 · 11~13자)은 바꿀 때만 이 규칙을 본다.
 */
@Component
@RequiredArgsConstructor
public class NicknamePolicy {

	public static final int MIN_LENGTH = 2;
	public static final int MAX_LENGTH = 10;
	private static final Pattern ALLOWED = Pattern.compile("^[가-힣A-Za-z0-9]{" + MIN_LENGTH + "," + MAX_LENGTH + "}$");

	private final MemberRepository memberRepository;
	private final ForbiddenWordFilter forbiddenWordFilter;

	public boolean isWellFormed(String nickname) {
		return nickname != null && ALLOWED.matcher(nickname).matches();
	}

	public boolean isForbidden(String nickname) {
		return forbiddenWordFilter.containsForbidden(nickname);
	}

	/** 살아 있는 다른 회원이 같은 이름(정규화 기준)을 쓰고 있는가. */
	public boolean isTaken(String nickname) {
		return memberRepository.existsByNicknameNormalizedAndDeletedAtIsNull(NicknameNormalizer.normalize(nickname));
	}

	/** 형식 → 금칙어 → 중복 순으로 보고 첫 문제를 던진다. */
	public void validate(String nickname) {
		if (!isWellFormed(nickname)) {
			throw new NicknameException(NicknameErrorCode.NICKNAME_FORMAT_INVALID);
		}
		if (isForbidden(nickname)) {
			throw new NicknameException(NicknameErrorCode.NICKNAME_FORBIDDEN);
		}
		if (isTaken(nickname)) {
			throw new NicknameException(NicknameErrorCode.NICKNAME_TAKEN);
		}
	}
}
