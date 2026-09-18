package sweetgirl.croissant.member.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.code.NicknameErrorCode;
import sweetgirl.croissant.member.dto.response.MemberProfileResponse;
import sweetgirl.croissant.member.dto.response.NicknameDrawResponse;
import sweetgirl.croissant.member.dto.response.NicknameDrawStatusResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.NicknameException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.util.NicknameGenerator;

/**
 * 닉네임 뽑기 — 사용자가 고르지 않고 서버가 랜덤으로 하나 뽑아 준다. 한국 날짜로 하루 한 번이고,
 * 뽑은 후보는 수락해야 닉네임이 된다. "원래대로"를 눌러도 그날 기회는 쓴 것이다(앱은 뽑기 전에 광고를 보여준다).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class NicknameDrawService {

	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private final MemberReader memberReader;
	private final MemberRepository memberRepository;
	private final NicknameGenerator nicknameGenerator;

	@Transactional(readOnly = true)
	public NicknameDrawStatusResponse getStatus(Long memberId) {
		Member member = memberReader.getMember(memberId);
		LocalDate today = LocalDate.now(ZONE);
		boolean canDraw = member.canDrawNickname(today);
		return new NicknameDrawStatusResponse(
			canDraw,
			member.getPendingNickname(),
			canDraw ? null : today.plusDays(1).atStartOfDay());
	}

	public NicknameDrawResponse draw(Long memberId) {
		Member member = memberReader.getMember(memberId);
		LocalDateTime now = LocalDateTime.now(ZONE);
		if (!member.canDrawNickname(now.toLocalDate())) {
			throw new NicknameException(NicknameErrorCode.NICKNAME_DRAW_LIMIT);
		}
		String candidate = nicknameGenerator.generate();
		member.drawNickname(candidate, now);
		return new NicknameDrawResponse(member.getNickname(), candidate);
	}

	public MemberProfileResponse accept(Long memberId) {
		Member member = memberReader.getMember(memberId);
		String pending = member.getPendingNickname();
		if (pending == null) {
			throw new NicknameException(NicknameErrorCode.NICKNAME_NO_PENDING);
		}
		// 큐는 서버마다 따로라 다른 서버가 같은 이름을 먼저 줬을 수 있다. 후보는 남겨 두고 "원래대로"로 비우게 한다.
		if (memberRepository.existsByNicknameAndDeletedAtIsNull(pending)) {
			throw new NicknameException(NicknameErrorCode.NICKNAME_TAKEN);
		}
		member.acceptPendingNickname();
		return MemberProfileResponse.from(member);
	}

	public MemberProfileResponse discard(Long memberId) {
		Member member = memberReader.getMember(memberId);
		member.discardPendingNickname();
		return MemberProfileResponse.from(member);
	}
}
