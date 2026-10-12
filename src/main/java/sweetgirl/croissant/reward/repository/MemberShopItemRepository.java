package sweetgirl.croissant.reward.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.reward.entity.MemberShopItem;

public interface MemberShopItemRepository extends JpaRepository<MemberShopItem, Long> {

	List<MemberShopItem> findAllByMemberIdOrderByIdAsc(Long memberId);

	boolean existsByMemberIdAndCharacterCodeAndItemCode(Long memberId, String characterCode, String itemCode);

	/**
	 * 산 것으로 남긴다. 남겼으면 1, 이미 가진 품목이면 0 이다.
	 *
	 * <p>읽어 보고 넣는 식이면 구매가 나란히 들어왔을 때 둘 다 "없다"를 보고 둘 다 값을 치른다.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO member_shop_items (member_id, character_code, item_code, purchased_at)
			VALUES (:memberId, :characterCode, :itemCode, :now)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(
			@Param("memberId") Long memberId,
			@Param("characterCode") String characterCode,
			@Param("itemCode") String itemCode,
			@Param("now") LocalDateTime now);
}
