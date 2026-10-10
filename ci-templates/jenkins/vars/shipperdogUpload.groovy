// shipperdogUpload -- the Jenkins leg of ShipperDog's CI story
// (S12-04, issue #54, FR-022). Wraps the shipperdog CLI: uploads the
// build artifact with provenance auto-captured from the Jenkins
// environment (GIT_COMMIT, BRANCH_NAME, BUILD_URL, JOB_NAME), polls
// validation to READY (the CLI's --wait), and fails the step (the
// CLI exits 4) with the failing stage when validation fails.
//
// Provenance is the S5-04 frozen contract; capturing it HERE (not by
// hand in every pipeline) is what lets POST /webhooks/jenkins match a
// completed build back to its artifacts.
//
// Call it from a Jenkinsfile (see Jenkinsfile.example beside this
// file):
//
//   shipperdogUpload file: 'app/build/outputs/apk/release/app-release.apk',
//                    app: 'banking', version: '7.12.0', platform: 'android'
//
// Required credentials: a ShipperDog PAT exposed to the step as the
// SHIPPERDOG_TOKEN environment variable (withCredentials / a
// 'usernamePassword' binding -- see the example). Least privilege is
// a dedicated DEVELOPER bot user whose only authority is
// app:artifacts:upload (docs/ci-jenkins.md).

def call(Map cfg = [:]) {
    def file = require(cfg, 'file', 'the artifact to upload (IPA/APK/AAB)')
    def app = require(cfg, 'app', 'the ShipperDog application name')
    def version = require(cfg, 'version', 'the version name (e.g. 7.12.0)')
    def platform = require(cfg, 'platform', 'ios or android')
    def timeout = cfg.get('timeout', '10m')

    withEnv(["SHIPPERDOG_API=${cfg.get('api', env.SHIPPERDOG_API)}"]) {
        sh label: 'shipperdog: upload', script: """
            set -eu
            shipperdog artifact upload '${file}' \
                --app '${app}' \
                --version '${version}' \
                --platform '${platform}' \
                --git-commit \"${env.GIT_COMMIT ?: ''}\" \
                --git-branch \"${env.BRANCH_NAME ?: env.GIT_BRANCH ?: ''}\" \
                --pipeline \"${env.JOB_NAME ?: ''}\" \
                --ci-run-url \"${env.BUILD_URL ?: ''}\" \
                --build-timestamp \"\$(date -u +%Y-%m-%dT%H:%M:%SZ)\" \
                --wait --timeout '${timeout}' --json
        """
    }
}

private static String require(Map cfg, String key, String what) {
    def value = cfg.get(key)
    if (!value) {
        error("shipperdogUpload: '${key}' is required (${what})")
    }
    return value
}
