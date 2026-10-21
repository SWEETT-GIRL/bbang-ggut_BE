package sweetgirl.croissant.world.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.world.entity.RewardedAdTransaction;

public interface RewardedAdTransactionRepository
		extends JpaRepository<RewardedAdTransaction, String> {

	/**
	 * 처음 보는 보상이면 이력을 남기고 1 을 준다. 이미 본 번호면 0 이다.
	 *
	 * <p>먼저 읽어 보고 없으면 넣는 식이면, 구글의 재시도가 나란히 들어왔을 때 둘 다 "없다"를 보고
	 * 둘 다 적립한다. PK 충돌을 DB 가 판단하게 두고 그 결과만 받는다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			INSERT INTO rewarded_ad_transaction
				(transaction_id, member_id, purpose, character_code, ad_unit, reward_amount, reward_item,
				created_at)
			VALUES (:transactionId, :memberId, 'CHARACTER', :characterCode, :adUnit, :rewardAmount, :rewardItem,
				CURRENT_TIMESTAMP)
			ON CONFLICT (transaction_id) DO NOTHING
			""", nativeQuery = true)
	int recordIfFirstTime(
			@Param("transactionId") String transactionId,
			@Param("memberId") Long memberId,
			@Param("characterCode") String characterCode,
			@Param("adUnit") String adUnit,
			@Param("rewardAmount") Integer rewardAmount,
			@Param("rewardItem") String rewardItem);

	/**
	 * 닉네임 뽑기 광고를 처음 보면 이력을 남기고 1 을 준다. 이미 본 번호면 0 이다.
	 * 친구가 없어 character_code 를 비운다 — 중복을 막는 방식은 {@link #recordIfFirstTime} 과 같다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			INSERT INTO rewarded_ad_transaction
				(transaction_id, member_id, purpose, ad_unit, reward_amount, reward_item, created_at)
			VALUES (:transactionId, :memberId, 'NICKNAME', :adUnit, :rewardAmount, :rewardItem, CURRENT_TIMESTAMP)
			ON CONFLICT (transaction_id) DO NOTHING
			""", nativeQuery = true)
	int recordNicknameIfFirstTime(
			@Param("transactionId") String transactionId,
			@Param("memberId") Long memberId,
			@Param("adUnit") String adUnit,
			@Param("rewardAmount") Integer rewardAmount,
			@Param("rewardItem") String rewardItem);

	/**
	 * 부스러기 광고를 처음 보면 이력을 남기고 1 을 준다. 이미 본 번호면 0 이다.
	 * 친구가 없어 character_code 를 비운다 — 중복을 막는 방식은 {@link #recordIfFirstTime} 과 같다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			INSERT INTO rewarded_ad_transaction
				(transaction_id, member_id, purpose, ad_unit, reward_amount, reward_item, created_at)
			VALUES (:transactionId, :memberId, 'CRUMBS', :adUnit, :rewardAmount, :rewardItem, CURRENT_TIMESTAMP)
			ON CONFLICT (transaction_id) DO NOTHING
			""", nativeQuery = true)
	int recordCrumbsIfFirstTime(
			@Param("transactionId") String transactionId,
			@Param("memberId") Long memberId,
			@Param("adUnit") String adUnit,
			@Param("rewardAmount") Integer rewardAmount,
			@Param("rewardItem") String rewardItem);

	/**
	 * 밥 룰렛 한 판 더 광고를 처음 보면 이력을 남기고 1 을 준다. 이미 본 번호면 0 이다.
	 * 친구가 없어 character_code 를 비운다 — 중복을 막는 방식은 {@link #recordIfFirstTime} 과 같다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			INSERT INTO rewarded_ad_transaction
				(transaction_id, member_id, purpose, ad_unit, reward_amount, reward_item, created_at)
			VALUES (:transactionId, :memberId, 'FEED', :adUnit, :rewardAmount, :rewardItem, CURRENT_TIMESTAMP)
			ON CONFLICT (transaction_id) DO NOTHING
			""", nativeQuery = true)
	int recordFeedIfFirstTime(
			@Param("transactionId") String transactionId,
			@Param("memberId") Long memberId,
			@Param("adUnit") String adUnit,
			@Param("rewardAmount") Integer rewardAmount,
			@Param("rewardItem") String rewardItem);
}
