package sweetgirl.croissant.member.dto.response;

import java.time.LocalDateTime;

/**
 * 광고를 보여주기 전에 앱이 먼저 묻는다 — 오늘 이미 뽑았는데 광고부터 보게 하면 안 된다.
 *
 * @param canDraw         오늘 뽑을 수 있는지
 * @param pendingNickname 뽑아 놓고 아직 수락도 "원래대로"도 안 누른 후보(없으면 null). 시트를 닫고 다시 왔을 때 이어서 보여준다
 * @param nextDrawAt      다음에 뽑을 수 있는 시각(Asia/Seoul). 지금 뽑을 수 있으면 null
 */
public record NicknameDrawStatusResponse(boolean canDraw, String pendingNickname, LocalDateTime nextDrawAt) {
}
