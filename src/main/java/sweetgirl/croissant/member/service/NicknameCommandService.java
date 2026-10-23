package sweetgirl.croissant.member.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.code.NicknameErrorCode;
import sweetgirl.croissant.member.dto.response.MemberProfileResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NicknameHistory;
import sweetgirl.croissant.member.exception.NicknameException;
import sweetgirl.croissant.member.repository.NicknameHistoryRepository;

/** 닉네임 직접 바꾸기. 뽑기는 {@link NicknameDrawService}. */
@Service
@RequiredArgsConstructor
@Transactional
public class NicknameCommandService {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;
	private final NicknamePolicy nicknamePolicy;
	private final NicknameHistoryRepository nicknameHistoryRepository;

	/**
	 * 게스트 403 MEMBER403_GUEST · 작성 제한 · 정지 403 · 프로필 제한 403 · 14일 안 429 NICKNAME429_COOLDOWN ·
	 * 형식 400 · 금칙어 400 · 중복 409. 같은 이름으로 바꾸는 건 아무것도 안 하고 200.
	 */
	public MemberProfileResponse change(Long memberId, String rawNickname) {
		memberReader.requireLoggedIn(memberId);
		memberRestrictionReader.validateCanWrite(memberId);
		memberRestrictionReader.validateProfileNotRestricted(memberId);
		Member member = memberReader.getMember(memberId);
		String nickname = NicknamePolicy.cleanInput(rawNickname);
		if (nickname.equals(member.getNickname())) {
			return MemberProfileResponse.from(member);
		}
		LocalDateTime now = LocalDateTime.now(KST);
		if (!member.canChangeNickname(now, Member.NICKNAME_CHANGE_INTERVAL_DAYS)) {
			throw new NicknameException(NicknameErrorCode.NICKNAME_COOLDOWN);
		}
		nicknamePolicy.validateForChange(nickname, member.getNicknameNormalized());
		nicknameHistoryRepository.save(NicknameHistory.of(memberId, member.getNickname(), now));
		member.changeNickname(nickname, now);
		return MemberProfileResponse.from(member);
	}
}
