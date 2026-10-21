package sweetgirl.dataops;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 가게 최신화(dataops) 판정 서버. batch 모듈 JAR 안에 함께 들어 있지만 WAS 배치 앱({@code sweetgirl.croissant})과는
 * 별개의 앱이다 — 패키지가 달라 서로 스캔하지 않고, 주 DB 도 dataops 스키마다.
 *
 * <p>설정은 {@code dataops.properties} 를 읽는다. 같은 JAR 의 WAS 배치 설정({@code application.properties})과 섞이지 않게
 * 파일 이름을 나눴다. 실행: {@code java -Dloader.main=sweetgirl.dataops.DataopsApplication -cp batch.jar
 * org.springframework.boot.loader.launch.PropertiesLauncher}
 */
@SpringBootApplication
public class DataopsApplication {

	public static final String CONFIG_NAME = "dataops";

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(DataopsApplication.class);
		application.setDefaultProperties(defaultProperties(Map.of()));
		application.run(args);
	}

	/** 진입점마다 넘기는 기본 설정에 설정 파일 이름을 더한다 */
	public static Map<String, Object> defaultProperties(Map<String, Object> properties) {
		Map<String, Object> merged = new HashMap<>(properties);
		merged.put("spring.config.name", CONFIG_NAME);
		return merged;
	}
}
