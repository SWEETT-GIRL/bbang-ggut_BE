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

	private final Queue<String> nicknameQueue = new ConcurrentLinkedQueue<>();

	@PostConstruct
	public void init() {
		List<String> adjectives = loadWords("nickname/adjectives.txt");
		List<String> nouns = loadWords("nickname/nouns.txt");

		List<String> all = new ArrayList<>(adjectives.size() * nouns.size());
		for (String adj : adjectives) {
			for (String noun : nouns) {
				all.add(adj + " " + noun);
			}
		}
		Collections.shuffle(all);

		Set<String> usedNicknames = new HashSet<>(memberRepository.findAllNicknames());
		for (String nickname : all) {
			if (!usedNicknames.contains(nickname)) {
				nicknameQueue.add(nickname);
			}
		}
	}

	public String generate() {
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
