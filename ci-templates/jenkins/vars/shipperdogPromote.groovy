// shipperdogPromote -- the Jenkins promotion step (S12-04, issue
// #54, FR-022). Wraps the CLI: walks a release to PROMOTED; a refused
// hop answers the COMPLETE blocker list and the step fails (the CLI
// exits 4 -- the branch point pipelines reason over).
//
//   shipperdogPromote release: 'banking/7.12.0/engineering', app: 'banking'
//
// Required credentials: a ShipperDog PAT exposed as SHIPPERDOG_TOKEN,
// held by a dedicated RELEASE_MANAGER bot user (app:releases:manage;
// splitting it from the upload token is the cheap duty separation --
// docs/ci-jenkins.md).

def call(Map cfg = [:]) {
    def release = require(cfg, 'release', 'the release reference (from release create --json)')
    def app = require(cfg, 'app', 'the ShipperDog application name')

    withEnv(["SHIPPERDOG_API=${cfg.get('api', env.SHIPPERDOG_API)}"]) {
        sh label: 'shipperdog: promote', script: """
            set -eu
            shipperdog release promote '${release}' --app '${app}' --json
        """
    }
}

private static String require(Map cfg, String key, String what) {
    def value = cfg.get(key)
    if (!value) {
        error("shipperdogPromote: '${key}' is required (${what})")
    }
    return value
}
