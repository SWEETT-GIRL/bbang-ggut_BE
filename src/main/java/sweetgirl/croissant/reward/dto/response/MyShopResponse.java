package sweetgirl.croissant.reward.dto.response;

import java.util.List;
import java.util.Map;

/**
 * 내가 산 것과 지금 쓰는 것.
 *
 * @param owned    산 품목. 모습은 어느 친구의 것인지가 함께 있고, 소품은 {@code characterCode} 가 null 이다
 * @param looks    친구별로 지금 하고 있는 모습(친구 코드 → 품목 코드). 없는 친구는 기본 모습이다
 * @param homeProp 홈에 놓은 소품. 없으면 null
 */
public record MyShopResponse(List<Owned> owned, Map<String, String> looks, String homeProp) {

	public record Owned(String characterCode, String itemCode) {
	}
}
