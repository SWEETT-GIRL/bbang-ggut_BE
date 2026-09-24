package sweetgirl.croissant.world.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 9/24 prod 에서 떨어진 진짜 콜백으로 검증한다. 키는 구글 공개키(key_id 3335741209) 그대로라
 * 네트워크 없이 돈다.
 */
class RewardedAdSignatureVerifierTest {

	private static final long KEY_ID = 3335741209L;
	private static final String KEY_BASE64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE"
		+ "+nzvoGqvDeB9+SzE6igTl7TyK4JBbglwir9oTcQta8NuG26ZpZFxt+F2NDk7asTE6/2Yc8i1ATcGIqtuS5hv0Q==";
	private static final String REAL_CALLBACK = "ad_network=5450213213286189855&ad_unit=6347447696"
		+ "&custom_data=67%3Ahamster&reward_amount=1&reward_item=ad_view&timestamp=1790210923208"
		+ "&transaction_id=00065c2ff3d3a26f07f540c73d30a0d7&user_id=67"
		+ "&signature=MEUCIQDWMGbM5A1VQ7EMO2LC8RdHAWA36FiJ7w3kwsPYmqbxpwIgUs9P_2Wlfi"
		+ "YSkhjtew6idTFu-9WCjzKTiRfIo_kP5v4"
		+ "&key_id=3335741209";

	private RewardedAdSignatureVerifier verifier;

	@BeforeEach
	void setUp() throws Exception {
		verifier = new RewardedAdSignatureVerifier();
		PublicKey key = KeyFactory.getInstance("EC")
			.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(KEY_BASE64)));
		ReflectionTestUtils.setField(verifier, "cachedKeys", new AtomicReference<>(Map.of(KEY_ID, key)));
	}

	@Test
	@DisplayName("custom_data 가 퍼센트 인코딩돼 와도 구글 서명을 통과한다")
	void verifiesEncodedCustomData() {
		assertThat(verifier.verify(REAL_CALLBACK)).isTrue();
	}

	@Test
	@DisplayName("값을 바꾸면 떨어진다")
	void rejectsTampered() {
		assertThat(verifier.verify(REAL_CALLBACK.replace("67%3Ahamster", "68%3Ahamster"))).isFalse();
	}
}
