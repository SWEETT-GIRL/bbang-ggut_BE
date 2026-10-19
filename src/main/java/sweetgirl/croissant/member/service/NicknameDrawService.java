package sweetgirl.croissant.member.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

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
 * 닉네임 뽑기 — 사용자가 고르지 않고 서버가 랜덤으로 하나 뽑아 준다. 뽑은 후보는 수락해야 닉네임이 된다.
 * "원래대로"를 눌러도 그 뽑기는 쓴 것이다.
 *
 * <p>주(월요일 0시 ~ 일요일 24시, Asia/Seoul)의 첫 뽑기는 무료로 앱이 직접 부른다. 그 뒤로는 광고를 볼 때마다
 * 바로 또 뽑는다 — 무료를 뽑은 그날에도, 횟수 제한 없이(10/19, 전엔 하루 한 번이었다).
 * 광고 뽑기는 광고 보상 콜백(SSV)이 {@link #drawWithAd} 로 서버에서 한다 — 앱이 "광고 봤어요"라고 말하는 것을 믿으면 그 요청만 흉내 내도 광고 없이 뽑힌다.
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
		LocalDate weekStart = weekStart(today);
		boolean freeUsed = member.usedFreeNicknameDraw(weekStart);
		boolean canDraw = !freeUsed;
		// 무료를 썼으면 광고로는 언제든 뽑는다. 그래서 무료로든 광고로든 늘 뽑을 수 있고 nextDrawAt 은 늘 null 이다
		return new NicknameDrawStatusResponse(
			canDraw,
			freeUsed,
			member.getPendingNickname(),
			null,
			freeUsed ? weekStart.plusWeeks(1).atStartOfDay() : null);
	}

	public NicknameDrawResponse draw(Long memberId) {
		Member member = memberReader.getMember(memberId);
		LocalDateTime now = LocalDateTime.now(ZONE);
		if (!member.canDrawNickname(now.toLocalDate())) {
			throw new NicknameException(NicknameErrorCode.NICKNAME_DRAW_LIMIT);
		}
		if (member.usedFreeNicknameDraw(weekStart(now.toLocalDate()))) {
			throw new NicknameException(NicknameErrorCode.NICKNAME_FREE_DRAW_USED);
		}
		String candidate = nicknameGenerator.generate();
		member.drawNickname(candidate, now);
		return new NicknameDrawResponse(member.getNickname(), candidate);
	}

	/**
	 * 광고 보상 콜백(SSV)에서만 부른다. 이번 주 무료를 이미 썼으면 광고 하나에 후보를 하나 뽑아 둔다(남은 후보는 덮는다).
	 * 같은 광고가 두 번 오는 건 콜백이 {@code transaction_id} 로 먼저 거른다.
	 * 앱은 광고가 끝나면 상태를 다시 물어 {@code pendingNickname} 이 생겼는지 본다.
	 *
	 * <p>던지지 않는다 — 콜백 트랜잭션을 롤백 전용으로 만들지 않도록, 못 뽑으면 false 만 준다.
	 *
	 * @return 뽑았으면 true
	 */
	public boolean drawWithAd(Long memberId) {
		LocalDateTime now = LocalDateTime.now(ZONE);
		LocalDate weekStart = weekStart(now.toLocalDate());
		boolean allowed = memberRepository.findByIdAndDeletedAtIsNull(memberId)
			.map(member -> member.usedFreeNicknameDraw(weekStart))
			.orElse(false);
		if (!allowed) {
			return false;
		}
		String candidate = nicknameGenerator.generate();
		return memberRepository.drawNicknameWithAdIfAllowed(
			memberId, candidate, now, weekStart.atStartOfDay()) == 1;
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

	private static LocalDate weekStart(LocalDate today) {
		return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}
}
