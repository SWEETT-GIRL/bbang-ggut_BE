package sweetgirl.croissant.world.service;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * AdMob 보상형 광고의 서버 보상 검증(SSV) 서명을 확인한다.
 *
 * <p>앱이 "광고 봤어요"라고 말하는 것만으로 친구를 깨우면, 그 요청을 흉내 내는 것만으로 광고 없이
 * 전부 해금된다. 구글이 서버로 직접 보내는 이 콜백만이 "정말 봤다"의 근거다.
 *
 * <p>검증 방법(구글 규격): 쿼리 문자열에서 {@code &signature=} 앞까지가 서명 대상이고,
 * 서명은 base64url, 키는 {@code key_id} 로 고른다. 알고리즘은 ECDSA P-256 / SHA-256.
 *
 * <p>키는 구글이 돌려가며 쓰므로 한 번 받아 들고 있다가, <b>모르는 {@code key_id} 를 만나면 다시
 * 받는다</b>. 시간 기반으로 갱신하면 키가 도는 순간의 콜백을 놓친다.
 */
@Slf4j
@Component
public class RewardedAdSignatureVerifier {

	private static final String SIGNATURE_PARAM = "&signature=";

	private final RestClient restClient = RestClient.create();
	private final AtomicReference<Map<Long, PublicKey>> cachedKeys = new AtomicReference<>(Map.of());

	@Value("${admob.ssv.verifier-keys-url:https://gstatic.com/admob/reward/verifier-keys.json}")
	private String verifierKeysUrl;

	/**
	 * @param query 콜백의 쿼리 문자열 전체(물음표 뒤). 구글이 서명한 그대로여야 하므로
	 *              파싱해서 다시 조립한 것이 아니라 원본이어야 한다
	 */
	public boolean verify(String query) {
		int signatureAt = query.indexOf(SIGNATURE_PARAM);
		if (signatureAt < 0) {
			return false;
		}
		String signedPart = query.substring(0, signatureAt);
		String tail = query.substring(signatureAt + SIGNATURE_PARAM.length());

		int keyIdAt = tail.indexOf("&key_id=");
		if (keyIdAt < 0) {
			return false;
		}
		String signature = tail.substring(0, keyIdAt);
		String keyId = tail.substring(keyIdAt + "&key_id=".length());

		PublicKey publicKey = findKey(keyId);
		if (publicKey == null) {
			log.warn("[AdMob SSV] 모르는 key_id={}", keyId);
			return false;
		}
		return matches(publicKey, signedPart, signature);
	}

	private PublicKey findKey(String keyId) {
		long parsed;
		try {
			parsed = Long.parseLong(keyId);
		} catch (NumberFormatException exception) {
			return null;
		}
		PublicKey cached = cachedKeys.get().get(parsed);
		if (cached != null) {
			return cached;
		}
		refreshKeys();
		return cachedKeys.get().get(parsed);
	}

	private void refreshKeys() {
		VerifierKeys response = restClient.get().uri(verifierKeysUrl).retrieve().body(VerifierKeys.class);
		if (response == null || response.keys() == null) {
			return;
		}
		Map<Long, PublicKey> keys = new java.util.HashMap<>();
		for (VerifierKey key : response.keys()) {
			toPublicKey(key.base64()).ifPresent(publicKey -> keys.put(key.keyId(), publicKey));
		}
		cachedKeys.set(Map.copyOf(keys));
	}

	private java.util.Optional<PublicKey> toPublicKey(String base64) {
		try {
			byte[] der = Base64.getDecoder().decode(base64);
			return java.util.Optional.of(
				KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der)));
		} catch (Exception exception) {
			log.warn("[AdMob SSV] 검증 키를 읽지 못했다", exception);
			return java.util.Optional.empty();
		}
	}

	private boolean matches(PublicKey publicKey, String signedPart, String signature) {
		try {
			Signature ecdsa = Signature.getInstance("SHA256withECDSA");
			ecdsa.initVerify(publicKey);
			ecdsa.update(signedPart.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return ecdsa.verify(Base64.getUrlDecoder().decode(signature));
		} catch (Exception exception) {
			log.warn("[AdMob SSV] 서명을 확인하지 못했다", exception);
			return false;
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record VerifierKeys(java.util.List<VerifierKey> keys) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record VerifierKey(long keyId, String pem, String base64) {
	}
}
