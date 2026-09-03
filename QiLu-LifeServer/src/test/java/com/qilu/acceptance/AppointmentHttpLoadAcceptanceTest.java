package com.qilu.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18081",
                "qilu.appointment.consumer.enabled=true"
        }
)
@EnabledIfSystemProperty(named = "acceptance.appointment-http", matches = "true")
class AppointmentHttpLoadAcceptanceTest {

    @Test
    void oneThousandUsersCompeteForOneHundredQuotaAcrossConfiguredRounds() throws Exception {
        Path serviceRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path repositoryRoot = serviceRoot.getParent();
        Path script = repositoryRoot.resolve("scripts/acceptance/run_appointment_http_acceptance.ps1");
        Path output = Path.of(System.getProperty(
                "acceptance.appointment-http.output",
                repositoryRoot.resolve("artifacts/acceptance/phase1-http").toString()
        )).toAbsolutePath().normalize();
        int rounds = Integer.getInteger("acceptance.appointment-http.rounds", 10);
        Path processLog = output.resolveSibling(output.getFileName() + "-run.log");

        Files.createDirectories(output);
        ProcessBuilder processBuilder = new ProcessBuilder(
                "powershell",
                "-ExecutionPolicy", "Bypass",
                "-File", script.toString(),
                "-OutputDirectory", output.toString(),
                "-Rounds", String.valueOf(rounds),
                "-BaseUrl", "http://127.0.0.1:18081"
        );
        processBuilder.directory(repositoryRoot.toFile());
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(processLog.toFile()));
        processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(processLog.toFile()));

        Process process = processBuilder.start();
        boolean finished = process.waitFor(Duration.ofMinutes(10).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
        }

        assertTrue(finished, "appointment HTTP acceptance timed out");
        assertEquals(0, process.exitValue(), "appointment HTTP acceptance process failed");
        assertTrue(Files.exists(output.resolve("appointment-http-acceptance.json")));
        assertTrue(Files.exists(output.resolve("sha256sums.txt")));
    }
}
