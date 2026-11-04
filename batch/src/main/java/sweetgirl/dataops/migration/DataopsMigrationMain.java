package sweetgirl.dataops.migration;

import java.net.URI;
import java.util.Map;

import org.flywaydb.core.Flyway;

/** 별도 dataops DB의 DDL만 실행한다. Spring 컨텍스트·수집·ServingWriter를 시작하지 않는다. */
public final class DataopsMigrationMain {

    private DataopsMigrationMain() {
    }

    public static void main(String[] args) {
        if (args.length != 0) {
            throw new IllegalArgumentException("Migration does not accept arguments");
        }
        Map<String, String> environment = System.getenv();
        String url = required(environment, "DATAOPS_DB_URL");
        validateTarget(url);
        Flyway.configure()
            .dataSource(url, required(environment, "DATAOPS_MIGRATION_DB_USERNAME"),
                required(environment, "DATAOPS_MIGRATION_DB_PASSWORD"))
            .locations("classpath:db/dataops-migration")
            .defaultSchema("public")
            .schemas("public")
            .createSchemas(false)
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .load()
            .migrate();
    }

    public static void validateTarget(String url) {
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("DATAOPS_DB_URL must be a PostgreSQL JDBC URL");
        }
        URI target = URI.create(url.substring("jdbc:".length()));
        if (target.getHost() == null || target.getUserInfo() != null || target.getFragment() != null
            || !"/dataops".equals(target.getPath())) {
            throw new IllegalArgumentException("Migration is restricted to database dataops");
        }
        if (target.getRawQuery() != null) {
            for (String option : target.getRawQuery().split("&", -1)) {
                if (!option.equals("currentSchema=public") && !option.equals("sslmode=require")
                    && !option.equals("sslmode=verify-full")) {
                    throw new IllegalArgumentException("Only public schema and explicit TLS options are allowed");
                }
            }
        }
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing environment variable: " + key);
        }
        return value;
    }
}
