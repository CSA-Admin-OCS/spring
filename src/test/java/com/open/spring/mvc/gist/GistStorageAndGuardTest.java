package com.open.spring.mvc.gist;

import java.util.Map;
import com.open.spring.mvc.backups.BackupsController;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import static org.junit.jupiter.api.Assertions.*;

class GistStorageAndGuardTest {
    @Test void migrationIsIdempotentAndExportOmitsOnlyCredentialTable() {
        var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var jdbc = new JdbcTemplate(source);
            var repo = new GistConnectionRepository(jdbc, source);
            repo.run(null); repo.save(1, "cipher-one", "alice"); repo.save(2, "cipher-two", "bob"); repo.run(null);
            repo.save(1, "cipher-replaced", "alice");
            assertEquals("cipher-replaced", repo.find(1).orElseThrow().ciphertext());
            assertEquals("bob", repo.find(2).orElseThrow().githubUsername());
            jdbc.execute("CREATE TABLE ordinary_data (id integer)");
            var controller = new BackupsController();
            ReflectionTestUtils.setField(controller, "dataSource", source);
            Map<?, ?> data = ReflectionTestUtils.invokeMethod(controller, "exportData");
            assertFalse(data.containsKey("gist_connections"));
            assertTrue(data.containsKey("ordinary_data"));
            repo.delete(1);
            assertTrue(repo.find(1).isEmpty()); assertTrue(repo.find(2).isPresent());
        } finally { source.destroy(); }
    }
    @Test void onlyTrustedOriginsWithCustomHeaderCanWrite() {
        var cors = new CorsConfiguration(); cors.addAllowedOrigin("http://localhost:4500");
        var guard = new GistRequestGuard(request -> cors);
        var request = new MockHttpServletRequest(); request.addHeader("Origin", "http://localhost:4500");
        assertThrows(GistException.class, () -> guard.validate(request));
        request.addHeader("X-Origin", "client"); assertDoesNotThrow(() -> guard.validate(request));
        request.removeHeader("Origin"); request.addHeader("Origin", "https://foreign.example");
        assertEquals(403, assertThrows(GistException.class, () -> guard.validate(request)).getStatus());
    }
}
