import sweetgirl.dataops.runtimeattestation.RuntimeMeasurement;
import sweetgirl.dataops.snapshot.parser.*;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Fixed offline parser-only Spring probe; never starts a datasource, web server or collector. */
public final class ReleaseArtifactProbe {
    public static void main(String[] args) {
        // Use the same scanned components and constructor injection as the service. A manual
        // new NaverPlaceParser would miss removed @Component or broken profile injection.
        // This package contains only pure parsers; no datasource, server or collector is started.
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.scan("sweetgirl.dataops.snapshot.parser");
            context.refresh();
            var mapper = context.getBean(ObjectMapper.class);
            var profile = context.getBean(MenuStructureProfile.class);
            var parser = context.getBean(NaverPlaceParser.class);
            if (parser.structureProfile() != profile)
                throw new IllegalStateException("PARSER_PROFILE_INJECTION_CHANGED");
            var diagnostics = new ResponseDiagnostics(mapper, parser, context.getBean(BusinessHoursParser.class),
                context.getBean(MenuEvidenceParser.class));
            System.out.println(mapper.writeValueAsString(Map.of(
                "parser_digest", diagnostics.runtimeParserDigest(),
                "profile_digest", profile.digest(),
                "contract_digest", RuntimeMeasurement.semanticContractDigest(),
                "contract_version", ResponseDiagnostics.CONTRACT)));
        }
    }
}
