package sweetgirl.croissant.follow.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.event.MemberPrivacyChangedEvent;

/** 계정을 공개로 돌리면 대기 중인 팔로우 요청을 전부 수락한다. 비공개로 돌릴 때는 기존 팔로워를 그대로 둔다. */
@Component
@RequiredArgsConstructor
public class FollowPrivacyListener {

	private final FollowRequestCommandService followRequestCommandService;

	@EventListener
	public void onPrivacyChanged(MemberPrivacyChangedEvent event) {
		if (!event.isPrivate()) {
			followRequestCommandService.acceptAllOf(event.memberId());
		}
	}
}
