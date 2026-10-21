package sweetgirl.croissant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 배치 EC2 에서 작업 하나를 실행하고 끝나는 앱. 엔티티·리포지토리를 core 와 같이 찾도록 루트 패키지에 둔다.
 */
@SpringBootApplication
public class BatchApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(BatchApplication.class, args);
		// Redis·Firebase 등의 스레드가 남아 JVM 이 내려가지 않는 일이 없도록 작업이 끝나면 명시적으로 종료한다.
		// 작업이 실패하면 run 이 예외를 던져 여기까지 오지 않고 종료 코드 1 로 끝난다.
		System.exit(SpringApplication.exit(context));
	}
}
