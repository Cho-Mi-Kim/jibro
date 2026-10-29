package org.jibro.global.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthControllerTest {
    private final JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
    private final HealthController controller = new HealthController(jdbcTemplate);

    @Test
    void databaseFailureIsReportedWithoutInternalDetails() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("internal connection details"));

        var response = controller.health();

        assertEquals(503, response.getStatusCode().value());
        assertEquals(new HealthController.HealthResponse("DOWN", "DOWN"), response.getBody());
    }

    @Test
    void respondsHealthyOnlyAfterDatabaseQuerySucceeds() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        var response = controller.health();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(new HealthController.HealthResponse("UP", "UP"), response.getBody());
    }
}
