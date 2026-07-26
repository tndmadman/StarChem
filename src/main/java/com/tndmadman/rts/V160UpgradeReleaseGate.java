package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Runs the historical upgrade gate from primary CI without burdening duplicate validation workflows. */
final class V160UpgradeReleaseGate {
    private static final long TIMEOUT_MINUTES = 15;

    private V160UpgradeReleaseGate() { }

    static void validateIfRequired() throws Exception {
        if (!"true".equalsIgnoreCase(System.getenv("CI"))) {
            System.out.println("Skipping v1.6.0 upgrade validation outside CI; run validation/run-v160-upgrade.sh manually.");
            return;
        }
        String workflow = System.getenv("GITHUB_WORKFLOW");
        if (workflow != null && !"CI".equals(workflow)) {
            System.out.println("Skipping duplicate v1.6.0 upgrade validation in workflow " + workflow + ".");
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            System.out.println("Skipping the bash-based v1.6.0 upgrade gate on Windows CI.");
            return;
        }

        Path root = Path.of("").toAbsolutePath().normalize();
        Path script = root.resolve("validation/run-v160-upgrade.sh");
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("v1.6.0 upgrade gate script is missing: " + script);
        }

        Process process = new ProcessBuilder("bash", script.toString(), System.getProperty("java.class.path"))
                .directory(root.toFile())
                .inheritIO()
                .start();
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("v1.6.0 upgrade validation timed out after " + TIMEOUT_MINUTES + " minutes");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("v1.6.0 upgrade validation failed with exit code " + process.exitValue());
        }
    }
}
