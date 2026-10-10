package com.shipperdog.api.artifact;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The ci-integration-smoke workflow's fixture generator (issue #53,
 * S12-03): writes one synthetic release APK to the path given as the
 * first argument, so the smoke run uploads a binary the validation
 * pipeline genuinely accepts -- the same canonical fixture generator
 * the test suites use (real Android binary XML, no committed binary,
 * no signing keys, nothing sensitive).
 *
 * <p>Not a test: a main()-only tool in the test tree (test-scope
 * dependencies only), run by the workflow as
 * {@code java -cp <test-classpath> com.shipperdog.api.artifact.SmokeFixtureTool out.apk}.
 * The exit-code seam ({@link #run(String[])}) exists so the guard
 * test can exercise the tool without System.exit ending the test JVM.
 */
public final class SmokeFixtureTool {

    private SmokeFixtureTool() {}

    public static void main(String[] arguments) {
        System.exit(run(arguments));
    }

    /** Writes the fixture; answers the process exit code (0 written, 2 usage). */
    static int run(String[] arguments) {
        if (arguments.length != 1) {
            System.err.println("usage: SmokeFixtureTool <output.apk>");
            return 2;
        }
        try {
            Path output = Path.of(arguments[0]);
            Files.write(output, AndroidFixtures.releaseApk());
            System.out.println("wrote " + Files.size(output) + " bytes to " + output.toAbsolutePath());
            return 0;
        } catch (Exception e) {
            System.err.println("cannot write the fixture: " + e.getMessage());
            return 1;
        }
    }
}
