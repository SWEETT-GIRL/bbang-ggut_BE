package sweetgirl.croissant.world.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.member.service.NicknameDrawService;
import sweetgirl.croissant.reward.service.EarnRewardService;
import sweetgirl.croissant.reward.service.RewardDailyCommandService;
import sweetgirl.croissant.world.entity.MemberCharacter;
import sweetgirl.croissant.world.entity.MemberCharacterId;
import sweetgirl.croissant.world.enums.AcquiredBy;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.repository.RewardedAdTransactionRepository;

/**
 * 구글이 서버로 직접 보내는 보상 검증 콜백을 받아 광고 한 편을 적립한다.
 *
 * <p><b>적립은 여기서만 한다.</b> 앱이 "광고 봤어요"라고 말하는 것을 믿으면, 그 요청을 흉내 내는
 * 것만으로 광고 없이 전부 해금되고 광고를 볼 이유가 사라진다.
 *
 * <p>누구의 어떤 친구인지는 {@code custom_data} 로 온다({@code memberId:characterCode}).
 * 앱이 광고를 띄울 때 실어 보내는 값이라, 형식이 어긋나면 적립할 곳을 알 수 없으므로 버린다.
 * 닉네임 뽑기 광고는 {@code nickname:memberId} 로 온다 — 친구 형식은 앞자리가 숫자라 겹치지 않는다.
 * 부스러기 광고는 {@code crumbs:memberId}, 밥 룰렛 한 판 더 광고는 {@code feed:memberId} 다.
 *
 * <p>구글은 콜백을 재시도한다. 같은 보상은 재시도돼도 {@code transaction_id} 가 같으므로,
 * 그 번호를 PK 로 둔 표에 먼저 남겨 보고 이미 있으면 버린다 — 중복 적립을 코드가 아니라 DB 가 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewardedAdSsvService {

	private static final String NICKNAME_PURPOSE = "nickname";
	/** 부스러기 광고는 {@code crumbs:memberId} 로 온다. 친구 형식은 앞자리가 숫자라 겹치지 않는다. */
	private static final String CRUMBS_PURPOSE = "crumbs";
	/** 밥 룰렛 한 판 더 광고는 {@code feed:memberId} 로 온다. */
	private static final String FEED_PURPOSE = "feed";

	private final RewardedAdSignatureVerifier signatureVerifier;
	private final MemberCharacterRepository memberCharacterRepository;
	private final WorldCatalogService worldCatalogService;
	private final WorldCommandService worldCommandService;
	private final RewardedAdTransactionRepository rewardedAdTransactionRepository;
	private final NicknameDrawService nicknameDrawService;
	private final EarnRewardService earnRewardService;
	private final RewardDailyCommandService rewardDailyCommandService;

	/**
	 * @param query 콜백 쿼리 문자열 원본. 구글이 서명한 그대로여야 해서 다시 조립한 값을 넘기면 안 된다
	 * @return 적립했으면 true. 서명이 안 맞거나 대상을 못 찾으면 false — 어느 쪽이든 응답은 200 이다
	 */
	@Transactional
	public boolean credit(String query) {
		if (query == null || !signatureVerifier.verify(query)) {
			log.warn("[AdMob SSV] 서명이 맞지 않아 버린다");
			return false;
		}

		Map<String, String> params = parse(query);
		String customData = params.get("custom_data");
		if (customData == null || !customData.contains(":")) {
			log.warn("[AdMob SSV] custom_data 가 없거나 형식이 다르다: {}", customData);
			return false;
		}

		String[] parts = customData.split(":", 2);
		if (NICKNAME_PURPOSE.equals(parts[0])) {
			return drawNickname(parts[1], params);
		}
		if (CRUMBS_PURPOSE.equals(parts[0])) {
			return grantCrumbs(parts[1], params);
		}
		if (FEED_PURPOSE.equals(parts[0])) {
			return openFeedExtra(parts[1], params);
		}
		Long memberId;
		try {
			memberId = Long.valueOf(parts[0]);
		} catch (NumberFormatException exception) {
			log.warn("[AdMob SSV] custom_data 의 회원 번호를 읽지 못했다: {}", customData);
			return false;
		}
		String characterCode = parts[1];

		// 앱이 막는 조건을 서버도 본다 — custom_data 를 바꿔 아직 못 닿은 행성의 친구를 깨우지 못하게.
		int adsRequired;
		try {
			adsRequired = worldCommandService.requireWakeable(memberId, characterCode).adsPerWake();
		} catch (WorldException exception) {
			log.warn("[AdMob SSV] 아직 깨울 수 없는 친구다 - member={}, character={}, 이유={}",
				memberId, characterCode, exception.getErrorCode().getCode());
			return false;
		}

		// 이미 처리한 보상이면 여기서 끝난다. 읽어 보고 넣는 식이면 재시도가 나란히 들어왔을 때 둘 다 통과한다.
		String transactionId = params.get("transaction_id");
		if (transactionId == null) {
			log.warn("[AdMob SSV] transaction_id 가 없어 중복을 가릴 수 없다");
			return false;
		}
		int recorded = rewardedAdTransactionRepository.recordIfFirstTime(
			transactionId,
			memberId,
			characterCode,
			params.get("ad_unit"),
			toInteger(params.get("reward_amount")),
			params.get("reward_item"));
		if (recorded == 0) {
			log.info("[AdMob SSV] 이미 처리한 보상이라 넘어간다 - transaction={}", transactionId);
			return false;
		}

		if (memberCharacterRepository.addAdView(memberId, characterCode) == 0) {
			log.info("[AdMob SSV] 이미 깨어난 친구라 적립하지 않는다 - member={}, character={}",
				memberId, characterCode);
			return false;
		}
		awakeIfEnough(memberId, characterCode, adsRequired);
		return true;
	}

	/**
	 * 닉네임 뽑기 광고. 친구 광고와 같이 이력 표에 먼저 남겨 보고, 이미 본 {@code transaction_id} 면 버린다 —
	 * 구글의 재시도가 나란히 들어와도 한 번만 뽑는다. 무료를 썼는지는 그 뒤에 {@link NicknameDrawService} 가 본다.
	 */
	private boolean drawNickname(String memberIdText, Map<String, String> params) {
		Long memberId;
		try {
			memberId = Long.valueOf(memberIdText);
		} catch (NumberFormatException exception) {
			log.warn("[AdMob SSV] 닉네임 광고의 회원 번호를 읽지 못했다: {}", memberIdText);
			return false;
		}

		String transactionId = params.get("transaction_id");
		if (transactionId == null) {
			log.warn("[AdMob SSV] transaction_id 가 없어 중복을 가릴 수 없다");
			return false;
		}
		int recorded = rewardedAdTransactionRepository.recordNicknameIfFirstTime(
			transactionId,
			memberId,
			params.get("ad_unit"),
			toInteger(params.get("reward_amount")),
			params.get("reward_item"));
		if (recorded == 0) {
			log.info("[AdMob SSV] 이미 처리한 보상이라 넘어간다 - transaction={}", transactionId);
			return false;
		}

		boolean drawn = nicknameDrawService.drawWithAd(memberId);
		log.info("[AdMob SSV] 닉네임 광고 - member={}, transaction={}, 뽑음={}", memberId, transactionId, drawn);
		return drawn;
	}

	/**
	 * 부스러기 광고. 이력 표에 먼저 남겨 보고, 이미 본 {@code transaction_id} 면 버린다.
	 * 하루 상한을 넘은 광고도 이력은 남긴다(정산 대조) — 부스러기만 주지 않는다.
	 */
	private boolean grantCrumbs(String memberIdText, Map<String, String> params) {
		Long memberId;
		try {
			memberId = Long.valueOf(memberIdText);
		} catch (NumberFormatException exception) {
			log.warn("[AdMob SSV] 부스러기 광고의 회원 번호를 읽지 못했다: {}", memberIdText);
			return false;
		}

		String transactionId = params.get("transaction_id");
		if (transactionId == null) {
			log.warn("[AdMob SSV] transaction_id 가 없어 중복을 가릴 수 없다");
			return false;
		}
		if (!earnRewardService.canEarn(memberId)) {
			log.warn("[AdMob SSV] 부스러기를 받을 수 없는 회원이다(없거나 게스트) - member={}", memberId);
			return false;
		}
		int recorded = rewardedAdTransactionRepository.recordCrumbsIfFirstTime(
			transactionId,
			memberId,
			params.get("ad_unit"),
			toInteger(params.get("reward_amount")),
			params.get("reward_item"));
		if (recorded == 0) {
			log.info("[AdMob SSV] 이미 처리한 보상이라 넘어간다 - transaction={}", transactionId);
			return false;
		}

		boolean granted = earnRewardService.rewardAdWatched(memberId, transactionId);
		log.info("[AdMob SSV] 부스러기 광고 - member={}, transaction={}, 지급={}", memberId, transactionId, granted);
		return granted;
	}

	/**
	 * 밥 룰렛 한 판 더 광고. 이력 표에 먼저 남겨 보고, 이미 본 {@code transaction_id} 면 버린다.
	 * 오늘 횟수를 다 썼거나 이미 열린 판이 있어도 이력은 남긴다(정산 대조) — 판만 열지 않는다.
	 */
	private boolean openFeedExtra(String memberIdText, Map<String, String> params) {
		Long memberId;
		try {
			memberId = Long.valueOf(memberIdText);
		} catch (NumberFormatException exception) {
			log.warn("[AdMob SSV] 밥 룰렛 광고의 회원 번호를 읽지 못했다: {}", memberIdText);
			return false;
		}

		String transactionId = params.get("transaction_id");
		if (transactionId == null) {
			log.warn("[AdMob SSV] transaction_id 가 없어 중복을 가릴 수 없다");
			return false;
		}
		if (!earnRewardService.canEarn(memberId)) {
			log.warn("[AdMob SSV] 밥을 더 줄 수 없는 회원이다(없거나 게스트) - member={}", memberId);
			return false;
		}
		int recorded = rewardedAdTransactionRepository.recordFeedIfFirstTime(
			transactionId,
			memberId,
			params.get("ad_unit"),
			toInteger(params.get("reward_amount")),
			params.get("reward_item"));
		if (recorded == 0) {
			log.info("[AdMob SSV] 이미 처리한 보상이라 넘어간다 - transaction={}", transactionId);
			return false;
		}

		boolean opened = rewardDailyCommandService.openFeedExtra(memberId);
		log.info("[AdMob SSV] 밥 룰렛 광고 - member={}, transaction={}, 한 판 열림={}", memberId, transactionId, opened);
		return opened;
	}

	/** 필요한 편수를 채웠으면 깨운다. 편수는 그 친구가 사는 행성 행에서 읽은 값이다. */
	private void awakeIfEnough(Long memberId, String characterCode, int adsRequired) {
		int adViews = memberCharacterRepository
			.findById(new MemberCharacterId(memberId, characterCode))
			.map(MemberCharacter::getAdViews)
			.orElse(0);

		if (adViews >= adsRequired) {
			memberCharacterRepository.awakeIfSleeping(memberId, characterCode, AcquiredBy.AD.name());
		}
	}

	private Integer toInteger(String value) {
		try {
			return value == null ? null : Integer.valueOf(value);
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private Map<String, String> parse(String query) {
		Map<String, String> params = new HashMap<>();
		Arrays.stream(query.split("&")).forEach(pair -> {
			int at = pair.indexOf('=');
			if (at > 0) {
				params.put(
					URLDecoder.decode(pair.substring(0, at), StandardCharsets.UTF_8),
					URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8));
			}
		});
		return params;
	}
}
