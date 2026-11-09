package sweetgirl.dataops.migration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DataopsMigrationMainTest {

    @Test
    void separateDatabaseIsAccepted() {
        assertDoesNotThrow(() -> DataopsMigrationMain.validateTarget(
            "jdbc:postgresql://localhost:15432/dataops?currentSchema=public&sslmode=require"));
    }

    @Test
    void servingDatabaseAndSchemaRedirectionAreRejected() {
        String[] rejected = {
            "jdbc:postgresql://localhost/soboro?currentSchema=dataops",
            "jdbc:postgresql://localhost/dataops?currentSchema=other",
            "jdbc:postgresql://localhost/dataops?options=-csearch_path%3Dother",
            "jdbc:postgresql://localhost/dataops?user=sweetgirl",
            "jdbc:postgresql://localhost/dataops?sslmode=disable",
            "jdbc:postgresql://user:secret@localhost/dataops",
            "jdbc:postgresql://localhost/dataops#fragment"
        };
        for (String url : rejected) {
            assertThrows(IllegalArgumentException.class, () -> DataopsMigrationMain.validateTarget(url));
        }
    }
}
