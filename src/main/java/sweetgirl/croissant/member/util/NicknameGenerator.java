package sweetgirl.croissant.member.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.member.code.NicknameErrorCode;
import sweetgirl.croissant.member.exception.NicknameException;
import sweetgirl.croissant.member.repository.MemberRepository;

@Component
@RequiredArgsConstructor
public class NicknameGenerator {

	private final MemberRepository memberRepository;

	/** NicknamePolicy.MAX_LENGTH 와 같은 값. 단어 파일 조합이 이걸 넘으면 그 조합은 버린다. */
	static final int NICKNAME_MAX_LENGTH = 10;
	static final int GENERATE_ATTEMPTS = 5;

	private final Queue<String> nicknameQueue = new ConcurrentLinkedQueue<>();

	@PostConstruct
	public void init() {
		List<String> adjectives = loadWords("nickname/adjectives.txt");
		List<String> nouns = loadWords("nickname/nouns.txt");

		// 새 닉네임 규칙(2026-10-21): 띄어쓰기 없이 붙이고 10자 안. 이미 있는 이름은 정규화해서 비교한다.
		List<String> all = new ArrayList<>(adjectives.size() * nouns.size());
		for (String adj : adjectives) {
			for (String noun : nouns) {
				String candidate = adj + noun;
				if (candidate.length() <= NICKNAME_MAX_LENGTH) {
					all.add(candidate);
				}
			}
		}
		Collections.shuffle(all);

		Set<String> usedNicknames = new HashSet<>();
		for (String used : memberRepository.findAllNicknames()) {
			usedNicknames.add(NicknameNormalizer.normalize(used));
		}
		for (String nickname : all) {
			if (!usedNicknames.contains(NicknameNormalizer.normalize(nickname))) {
				nicknameQueue.add(nickname);
			}
		}
	}

	/**
	 * 큐는 기동 때의 이름만 뺀 것이라, 그 뒤 누가 같은 이름으로 바꿨거나 다른 인스턴스가 먼저 줬을 수 있다 —
	 * 살아 있는 회원 유니크(uq_member_nickname_normalized_alive)에 걸리기 전에 DB 로 한 번 더 본다.
	 */
	public String generate() {
		for (int attempt = 0; attempt < GENERATE_ATTEMPTS; attempt++) {
			String nickname = pollOrRebuild();
			if (!memberRepository
				.existsByNicknameNormalizedAndDeletedAtIsNull(NicknameNormalizer.normalize(nickname))) {
				return nickname;
			}
		}
		throw new NicknameException(NicknameErrorCode.NICKNAME_EXHAUSTED);
	}

	private String pollOrRebuild() {
		String nickname = nicknameQueue.poll();
		if (nickname == null) {
			rebuild();
			nickname = nicknameQueue.poll();
		}
		if (nickname == null) {
			throw new NicknameException(NicknameErrorCode.NICKNAME_EXHAUSTED);
		}
		return nickname;
	}

	private synchronized void rebuild() {
		if (!nicknameQueue.isEmpty()) {
			return;
		}
		init();
	}

	// 단어 파일에 같은 단어가 두 줄 있으면 그 단어가 든 조합이 큐에 두 번 들어가 같은 닉네임이 두 명에게 나간다.
	private List<String> loadWords(String path) {
		Set<String> words = new LinkedHashSet<>();
		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(
				new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String word = line.trim();
				if (!word.isEmpty()) {
					words.add(word);
				}
			}
		} catch (IOException e) {
			throw new NicknameException(NicknameErrorCode.NICKNAME_FILE_LOAD_FAILED, e);
		}
		return new ArrayList<>(words);
	}
}
