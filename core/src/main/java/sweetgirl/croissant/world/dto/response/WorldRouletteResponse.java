package sweetgirl.croissant.world.dto.response;

import java.util.List;

/**
 * 룰렛 결과. 뽑기는 앱이 아니라 서버가 한다 — 앱이 뽑으면 결과를 앱이 정하게 된다.
 *
 * @param characterCodes 이번에 깨어난 친구들. 행성 행의 {@code rouletteSize} 만큼이다
 */
public record WorldRouletteResponse(List<String> characterCodes) {
}
