package sweetgirl.croissant.breadlog.service;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.follow.service.FollowVisibilityReader;
import sweetgirl.croissant.member.repository.MemberBlockRepository;

/**
 * 남의 빵로그를 볼 수 있는가 — 공개이고, 숨기지 않았고, 작성자가 탈퇴하지 않았고, 보는 사람과 차단 관계가 아니어야 한다.
 * 작성자가 비공개 계정이면 팔로워여야 한다. 내 것은 비공개 · 숨김이어도 본다(숨김은 "검토 중"으로 표시).
 */
@Component
@RequiredArgsConstructor
public class BreadRecordVisibilityPolicy {

	private final MemberBlockRepository memberBlockRepository;
	private final FollowVisibilityReader followVisibilityReader;

	public boolean canView(Long viewerId, BreadRecord record) {
		Long authorId = record.getMember().getId();
		if (authorId.equals(viewerId)) {
			return true;
		}
		if (!record.isPublic() || record.isHidden() || record.getMember().isDeleted()) {
			return false;
		}
		if (!followVisibilityReader.canViewRecords(viewerId, record.getMember())) {
			return false;
		}
		return viewerId == null || !memberBlockRepository.existsBetween(viewerId, authorId);
	}

	/** 볼 수 없으면 403 — 비공개인지 숨김인지 차단인지는 알려 주지 않는다("볼 수 없어요"). */
	public void validateCanView(Long viewerId, BreadRecord record) {
		if (!canView(viewerId, record)) {
			throw new BreadLogException(BreadLogErrorCode.BREAD_RECORD_NOT_VISIBLE);
		}
	}
}
