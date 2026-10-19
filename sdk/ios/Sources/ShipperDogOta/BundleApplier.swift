import Foundation

/// The app-supplied apply callback's declared outcome (issue #175,
/// S16-13): `.applied` reports OTA_APPLIED SUCCEEDED; `.refused`
/// reports OTA_APPLIED FAILED — the app's considered refusal (a
/// low-battery deferral, a consent gate), distinct from a THROWN
/// error, which reports OTA_FAILED apply_error (an attempted apply
/// that broke).
public enum ApplyResult {
    case applied
    case refused
}

/// The app-supplied apply callback (issue #175, S16-13; ADR-0010):
/// the SDK verifies and hands over the staged bundle — swapping it
/// in, hot-loading it, or staging it for the next launch is the
/// HOST's own act, because only the app knows its bundle format and
/// its apply mechanics. The callback runs AFTER verification, never
/// before (the acceptance criterion's own ordering), on the thread
/// the host called `OtaUpdater.updateNow` from.
///
/// The post-apply rollback verdict — the app detecting a crash loop
/// and reverting — is reported separately through
/// `OtaUpdater.reportRolledBack(detail:)`.
public protocol BundleApplier: AnyObject {

    /// Applies the VERIFIED bundle the manifest described.
    func apply(_ manifest: OtaManifest, bundle: URL) throws -> ApplyResult
}
