package sweetgirl.croissant.member.service;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.OAuthRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;

/**
 * 탈퇴한 지 {@code member.withdrawal-retention.days}(기본 30)일이 지난 회원에게 아직 남은 개인 정보를 지운다.
 *
 * <p>지금은 탈퇴할 때 바로 지우므로({@code MemberCommandService#deleteMember}) 이 작업이 할 일은 그 전에 탈퇴한 회원의 남은 값
 * 정도다. 행은 지우지 않는다(표·컬럼 변경 없음) — 리워드 원장·기록·코스·신고가 member id 를 FK 로 가리킨다.
 * 소셜 providerId(SHA-256 해시)는 재가입 보상 중복·강퇴 회피를 막는 데 쓰여 남긴다.
 *
 * <p>대상이 없으면 네 문장 모두 0건으로 끝나므로 매일 돌려도 된다.
 */
@Slf4j
@Service
public class WithdrawnMemberRetentionService {

	private final MemberRepository memberRepository;
	private final OAuthRepository oAuthRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final FcmTokenRepository fcmTokenRepository;
	private final int retentionDays;

	public WithdrawnMemberRetentionService(
			MemberRepository memberRepository,
			OAuthRepository oAuthRepository,
			RefreshTokenRepository refreshTokenRepository,
			FcmTokenRepository fcmTokenRepository,
			@Value("${member.withdrawal-retention.days:30}") int retentionDays) {
		this.memberRepository = memberRepository;
		this.oAuthRepository = oAuthRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.fcmTokenRepository = fcmTokenRepository;
		this.retentionDays = retentionDays;
	}

	@Transactional
	public Result anonymizeExpired(LocalDateTime now) {
		LocalDateTime cutoff = now.minusDays(retentionDays);
		Result result = new Result(
			memberRepository.anonymizeMembersDeletedBefore(cutoff, Member.WITHDRAWN_NICKNAME),
			oAuthRepository.clearEmailOfMembersDeletedBefore(cutoff),
			refreshTokenRepository.revokeAllOfMembersDeletedBefore(cutoff, now),
			fcmTokenRepository.deleteAllOfMembersDeletedBefore(cutoff));
		log.info("[탈퇴 회원 정리] cutoff={} nicknames={} emails={} refreshTokens={} fcmTokens={}",
			cutoff, result.nicknames(), result.emails(), result.refreshTokens(), result.fcmTokens());
		return result;
	}

	public record Result(int nicknames, int emails, int refreshTokens, int fcmTokens) {
	}
}
