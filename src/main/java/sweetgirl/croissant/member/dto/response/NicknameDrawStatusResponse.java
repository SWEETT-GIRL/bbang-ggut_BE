package sweetgirl.croissant.member.dto.response;

import java.time.LocalDateTime;

/**
 * 광고를 보여주기 전에 앱이 먼저 묻는다 — 무료가 남았는데 광고부터 보게 하면 안 된다.
 *
 * <p>규칙: 주(월요일 0시 ~ 일요일 24시, Asia/Seoul)의 첫 뽑기만 무료다. 그 뒤로는 광고를 볼 때마다 뽑고
 * 횟수 제한이 없다(10/19). 광고 뽑기는 앱이 아니라 광고 보상 콜백(SSV)이 서버에서 뽑는다.
 *
 * @param canDraw         지금 무료로 뽑을 수 있는지(POST 가 통한다)
 * @param canDrawWithAd   무료를 써서 광고를 보면 뽑을 수 있는지
 * @param pendingNickname 뽑아 놓고 아직 수락도 "원래대로"도 안 누른 후보(없으면 null). 시트를 닫고 다시 왔을 때 이어서 보여준다
 * @param nextDrawAt      늘 null — 무료로든 광고로든 언제든 뽑을 수 있다. 예전 앱이 읽어서 자리만 남겼다
 * @param nextFreeDrawAt  다음 무료 뽑기 시각(다음 주 월요일 0시, Asia/Seoul). 이번 주 무료가 남았으면 null
 */
public record NicknameDrawStatusResponse(
		boolean canDraw,
		boolean canDrawWithAd,
		String pendingNickname,
		LocalDateTime nextDrawAt,
		LocalDateTime nextFreeDrawAt) {
}
