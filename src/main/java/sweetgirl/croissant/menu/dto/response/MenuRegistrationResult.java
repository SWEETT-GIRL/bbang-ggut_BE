package sweetgirl.croissant.menu.dto.response;

/**
 * @param suggestedKind 화면에 칩으로 보여 줄 AI 제안 kind. 확정이 아니다 — 캐시에 3회 일치 판정이 있으면 그것,
 *                      처음 보는 이름이면 1회 즉답, 못 받았거나 비-빵이면 null
 */
public record MenuRegistrationResult(Long menuId, boolean created, String suggestedKind) {
}
