package sweetgirl.croissant.world.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.sun.net.httpserver.HttpServer;

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

	private static final String KEYS_JSON = "{\"keys\":[{\"keyId\":" + KEY_ID + ",\"base64\":\"" + KEY_BASE64 + "\"}]}";

	private RewardedAdSignatureVerifier verifier;
	private HttpServer keyServer;
	private final AtomicInteger keyFetches = new AtomicInteger();
	/** 키 서버가 응답을 붙들고 있게 할 때 쓴다 */
	private volatile CountDownLatch holdKeyResponse = new CountDownLatch(0);

	@BeforeEach
	void setUp() throws Exception {
		verifier = new RewardedAdSignatureVerifier();
		PublicKey key = KeyFactory.getInstance("EC")
			.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(KEY_BASE64)));
		ReflectionTestUtils.setField(verifier, "cachedKeys", new AtomicReference<>(Map.of(KEY_ID, key)));
	}

	@AfterEach
	void tearDown() {
		if (keyServer != null) {
			keyServer.stop(0);
		}
	}

	/** 캐시를 비우고 로컬 키 서버를 바라보게 한다. 받으러 온 횟수를 센다 */
	private RewardedAdSignatureVerifier verifierWithKeyServer() throws IOException {
		keyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		keyServer.setExecutor(Executors.newCachedThreadPool());
		keyServer.createContext("/keys", exchange -> {
			keyFetches.incrementAndGet();
			try {
				holdKeyResponse.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			byte[] body = KEYS_JSON.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		keyServer.start();
		RewardedAdSignatureVerifier fresh = new RewardedAdSignatureVerifier();
		ReflectionTestUtils.setField(fresh, "verifierKeysUrl",
			"http://127.0.0.1:" + keyServer.getAddress().getPort() + "/keys");
		return fresh;
	}

	@Test
	@DisplayName("처음 보는 key_id 면 키를 받아 와서 검증한다 — 키가 도는 순간의 콜백을 놓치지 않는다")
	void fetchesKeysForUnknownKeyId() throws IOException {
		RewardedAdSignatureVerifier fresh = verifierWithKeyServer();

		assertThat(fresh.verify(REAL_CALLBACK)).isTrue();
		assertThat(fresh.verify(REAL_CALLBACK)).isTrue();
		assertThat(keyFetches).hasValue(1);
	}

	@Test
	@DisplayName("모르는 key_id 가 계속 와도 60초에 한 번만 받으러 간다 — 그 사이엔 받지 않고 떨어뜨린다")
	void throttlesRefetchForUnknownKeyId() throws IOException {
		RewardedAdSignatureVerifier fresh = verifierWithKeyServer();
		String forged = REAL_CALLBACK.replace("key_id=3335741209", "key_id=1");

		for (int i = 0; i < 5; i++) {
			assertThat(fresh.verify(forged)).isFalse();
		}
		assertThat(keyFetches).hasValue(1);
	}

	@Test
	@DisplayName("나란히 들어와도 한 스레드만 받으러 가고, 나머지는 기다리지 않고 떨어진다")
	void concurrentUnknownKeysFetchOnce() throws Exception {
		RewardedAdSignatureVerifier fresh = verifierWithKeyServer();
		holdKeyResponse = new CountDownLatch(1);
		String forged = REAL_CALLBACK.replace("key_id=3335741209", "key_id=1");
		ExecutorService callers = Executors.newFixedThreadPool(8);
		try {
			Future<Boolean> first = callers.submit(() -> fresh.verify(forged));
			// 첫 호출이 키 서버에 닿을 때까지 기다린다
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (keyFetches.get() == 0 && System.nanoTime() < deadline) {
				Thread.onSpinWait();
			}
			List<Future<Boolean>> others = new ArrayList<>();
			for (int i = 0; i < 7; i++) {
				others.add(callers.submit(() -> fresh.verify(forged)));
			}
			for (Future<Boolean> other : others) {
				// 받는 중인 스레드를 기다리지 않으므로 키 서버가 붙들고 있어도 바로 끝난다
				assertThat(other.get(1, TimeUnit.SECONDS)).isFalse();
			}
			holdKeyResponse.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS)).isFalse();
			assertThat(keyFetches).hasValue(1);
		} finally {
			holdKeyResponse.countDown();
			callers.shutdownNow();
		}
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
