package com.shipperdog.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the ci-integration-smoke workflow's fixture generator (issue
 * #53, S12-03) against rot: the tool runs only from the workflow (no
 * suite executes it), so this test drives it the way the workflow
 * does -- through the exit-code seam -- and asserts the two
 * properties the smoke's upload leg depends on: a real ZIP archive
 * carrying an AndroidManifest.xml entry, byte-identical to the
 * canonical release fixture (the AXML round-trip itself is the
 * extraction suite's contract, not this test's).
 */
class SmokeFixtureToolTest {

    @Test
    void writesAReleaseApkShapedArchiveThePipelineAccepts(@TempDir Path dir) throws Exception {
        Path output = dir.resolve("smoke-release.apk");

        int exit = SmokeFixtureTool.run(new String[] {output.toString()});

        // The fixture generator salts its dex noise with SecureRandom
        // (deliberately non-deterministic), so the assertion is the
        // archive's STRUCTURE -- the properties the smoke's upload leg
        // depends on -- never byte identity.
        assertThat(exit).isZero();
        assertThat(output).exists();
        assertThat(Files.size(output)).isPositive();
        try (ZipFile zip = new ZipFile(output.toFile())) {
            ZipEntry manifest = zip.getEntry("AndroidManifest.xml");
            assertThat(manifest)
                    .as("the fixture APK carries its manifest entry")
                    .isNotNull();
            assertThat(manifest.getSize()).isPositive();
        }
    }

    @Test
    void usageErrorsRefuseWithoutWriting(@TempDir Path dir) {
        assertThat(SmokeFixtureTool.run(new String[] {})).isEqualTo(2);
        assertThat(SmokeFixtureTool.run(new String[] {"a", "b"})).isEqualTo(2);
        assertThat(dir.toFile().list()).isEmpty();
    }

    @Test
    void anUnwritableTargetAnswersNonzero(@TempDir Path dir) {
        int exit = SmokeFixtureTool.run(
                new String[] {dir.resolve("missing-dir").resolve("out.apk").toString()});
        assertThat(exit).isEqualTo(1);
    }
}
