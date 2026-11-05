package sweetgirl.croissant.member.service;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
import sweetgirl.croissant.abuse.repository.ModerationActionRepository;
import sweetgirl.croissant.abuse.repository.ModerationAppealRepository;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;
import sweetgirl.croissant.member.repository.OAuthRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;
import sweetgirl.croissant.member.util.NicknameNormalizer;

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
	private final AbuseReportRepository abuseReportRepository;
	private final ModerationActionRepository moderationActionRepository;
	private final ModerationAppealRepository moderationAppealRepository;
	private final MemberRestrictionRepository memberRestrictionRepository;
	private final MemberBlockRepository memberBlockRepository;
	private final int retentionDays;
	private final int moderationRetentionDays;

	public WithdrawnMemberRetentionService(
			MemberRepository memberRepository,
			OAuthRepository oAuthRepository,
			RefreshTokenRepository refreshTokenRepository,
			FcmTokenRepository fcmTokenRepository,
			AbuseReportRepository abuseReportRepository,
			ModerationActionRepository moderationActionRepository,
			ModerationAppealRepository moderationAppealRepository,
			MemberRestrictionRepository memberRestrictionRepository,
			MemberBlockRepository memberBlockRepository,
			@Value("${member.withdrawal-retention.days:30}") int retentionDays,
			@Value("${member.withdrawal-retention.moderation-days:365}") int moderationRetentionDays) {
		this.memberRepository = memberRepository;
		this.oAuthRepository = oAuthRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.fcmTokenRepository = fcmTokenRepository;
		this.abuseReportRepository = abuseReportRepository;
		this.moderationActionRepository = moderationActionRepository;
		this.moderationAppealRepository = moderationAppealRepository;
		this.memberRestrictionRepository = memberRestrictionRepository;
		this.memberBlockRepository = memberBlockRepository;
		this.retentionDays = retentionDays;
		this.moderationRetentionDays = moderationRetentionDays;
	}

	/**
	 * 탈퇴한 지 {@code member.withdrawal-retention.moderation-days}(기본 365)일이 지난 회원의 신고 · 제재 · 차단 기록을 지운다 —
	 * 개인정보처리방침의 "신고 · 제재 기록은 탈퇴 후 1년" 과 같은 숫자다. 이의제기 → 조치 → 신고 순으로 지워 FK 가 걸리지 않게 한다.
	 */
	@Transactional
	public ModerationPurge purgeExpiredModerationRecords(LocalDateTime now) {
		LocalDateTime cutoff = now.minusDays(moderationRetentionDays);
		// 신고자만 탈퇴한 신고에서 비롯된 활성 회원의 조치 · 이의제기는 남긴다 — 신고 행만 끊는다(report = null)
		moderationActionRepository.detachReportsOfMembersDeletedBefore(cutoff);
		ModerationPurge purge = new ModerationPurge(
			moderationAppealRepository.deleteAllOfMembersDeletedBefore(cutoff),
			moderationActionRepository.deleteAllOfMembersDeletedBefore(cutoff),
			abuseReportRepository.deleteAllOfMembersDeletedBefore(cutoff),
			memberRestrictionRepository.deleteAllOfMembersDeletedBefore(cutoff),
			memberBlockRepository.deleteAllOfMembersDeletedBefore(cutoff));
		log.info("[탈퇴 회원 신고 · 제재 기록 정리] cutoff={} appeals={} actions={} reports={} restrictions={} blocks={}",
			cutoff, purge.appeals(), purge.actions(), purge.reports(), purge.restrictions(), purge.blocks());
		return purge;
	}

	public record ModerationPurge(int appeals, int actions, int reports, int restrictions, int blocks) {
	}

	@Transactional
	public Result anonymizeExpired(LocalDateTime now) {
		LocalDateTime cutoff = now.minusDays(retentionDays);
		Result result = new Result(
			memberRepository.anonymizeMembersDeletedBefore(cutoff, Member.WITHDRAWN_NICKNAME,
				NicknameNormalizer.normalize(Member.WITHDRAWN_NICKNAME)),
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
