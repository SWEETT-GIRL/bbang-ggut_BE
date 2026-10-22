package sweetgirl.croissant.breadlog.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.service.CommunityNotificationService;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordLikeRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;

/** 공개 빵로그 좋아요. 멱등이고 부스러기는 없다. */
@Service
@RequiredArgsConstructor
@Transactional
public class BreadRecordLikeCommandService {

	private final BreadRecordRepository breadRecordRepository;
	private final BreadRecordLikeRepository breadRecordLikeRepository;
	private final BreadRecordVisibilityPolicy visibilityPolicy;
	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;
	private final CommunityNotificationService communityNotificationService;

	/** 게스트 403 MEMBER403_GUEST · 정지 403 · 없음 404 · 볼 수 없는 기록(비공개 · 숨김 · 차단) 403. 내 기록도 누를 수 있다. */
	public void like(Long memberId, Long recordId) {
		memberReader.requireLoggedIn(memberId);
		memberRestrictionReader.validateCanInteract(memberId);
		BreadRecord record = breadRecordRepository.findByIdAndDeletedAtIsNull(recordId)
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.BREAD_RECORD_NOT_FOUND));
		visibilityPolicy.validateCanView(memberId, record);
		if (!record.isPublic()) {
			throw new BreadLogException(BreadLogErrorCode.BREAD_RECORD_NOT_VISIBLE);
		}
		if (breadRecordLikeRepository.insertIfAbsent(record.getId(), memberId, LocalDateTime.now()) == 1) {
			communityNotificationService.notifyRecordLike(memberReader.getMember(memberId), record);
		}
	}

	/** 안 눌렀어도 200. 비공개로 돌아간 기록의 좋아요도 뗄 수 있다. */
	public void unlike(Long memberId, Long recordId) {
		memberReader.requireLoggedIn(memberId);
		breadRecordLikeRepository.deleteByPair(recordId, memberId);
	}
}
