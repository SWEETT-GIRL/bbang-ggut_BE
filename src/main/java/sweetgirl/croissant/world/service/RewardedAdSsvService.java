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
import sweetgirl.croissant.world.entity.MemberCharacter;
import sweetgirl.croissant.world.entity.MemberCharacterId;
import sweetgirl.croissant.world.enums.AcquiredBy;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;

/**
 * 구글이 서버로 직접 보내는 보상 검증 콜백을 받아 광고 한 편을 적립한다.
 *
 * <p><b>적립은 여기서만 한다.</b> 앱이 "광고 봤어요"라고 말하는 것을 믿으면, 그 요청을 흉내 내는
 * 것만으로 광고 없이 전부 해금되고 광고를 볼 이유가 사라진다.
 *
 * <p>누구의 어떤 친구인지는 {@code custom_data} 로 온다({@code memberId:characterCode}).
 * 앱이 광고를 띄울 때 실어 보내는 값이라, 형식이 어긋나면 적립할 곳을 알 수 없으므로 버린다.
 *
 * <p>같은 콜백이 두 번 와도(구글은 재시도한다) 두 번 적립되지 않게 해야 하는데, 지금은
 * 거래 번호를 저장할 자리가 없어 막지 못한다 — §남은 일 참고.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewardedAdSsvService {

	private final RewardedAdSignatureVerifier signatureVerifier;
	private final MemberCharacterRepository memberCharacterRepository;
	private final WorldCatalogService worldCatalogService;
	private final WorldCommandService worldCommandService;

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

		if (memberCharacterRepository.addAdView(memberId, characterCode) == 0) {
			log.info("[AdMob SSV] 이미 깨어난 친구라 적립하지 않는다 - member={}, character={}",
				memberId, characterCode);
			return false;
		}
		awakeIfEnough(memberId, characterCode, adsRequired);
		return true;
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
