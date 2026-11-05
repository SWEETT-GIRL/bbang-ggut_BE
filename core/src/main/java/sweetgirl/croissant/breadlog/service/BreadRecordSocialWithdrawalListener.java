package sweetgirl.croissant.breadlog.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordCompanionRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordLikeRepository;
import sweetgirl.croissant.member.event.MemberWithdrawnEvent;

/** 탈퇴하면 그 사람이 누른 좋아요와 같이 간 사람 태그(태그된 것 · 자기 기록에 단 것)를 지운다. 빵로그는 안 보이게만 남는다. */
@Component
@RequiredArgsConstructor
public class BreadRecordSocialWithdrawalListener {

	private final BreadRecordLikeRepository likeRepository;
	private final BreadRecordCompanionRepository companionRepository;

	@EventListener
	public void onWithdrawn(MemberWithdrawnEvent event) {
		likeRepository.deleteAllByMemberId(event.memberId());
		companionRepository.deleteAllOf(event.memberId());
	}
}
