import Foundation

/// The OTA updater (issue #175, S16-13; ADR-0010 workstream 9): the
/// complete device funnel — check, download, verify, apply, report —
/// driven by the host, the telemetry beacon's own doctrine (no
/// timers, no services, no background threads in the core).
///
/// The check loop's shape: the host calls `checkNow()` (or the full
/// `updateNow`) on ITS cadence — the demo wires a repeating dispatch
/// timer at `nextPollSeconds()`, the manifest's server-owned poll
/// cadence (D9), falling back to the config's until the first
/// manifest. Every check presents the `sdc1.` app token; 204 is the
/// no-op standing (up-to-date, not eligible, halted —
/// indistinguishable on the wire, D9); 200 is the manifest the
/// funnel then walks.
///
/// Every transition reports through the lifecycle queue: seen on the
/// served manifest, the download's two legs, the verifier's two
/// failure words, the applier's outcome, the thrown-apply
/// apply_error, and the app-reported rollback — flushed at the
/// cycle's end and on the host's next call (at-least-once, deduped
/// server-side by event id). The #174 review's liveness lessons are
/// structural here: the seen report's enqueue is guarded (its
/// failure drops one report, never the check's standing), and every
/// flush is quiet (a report leg never decides the funnel's answer) —
/// the host's re-arming loop has no failure path to arm around.
///
/// Thread-safe: one update cycle at a time (a second concurrent
/// caller waits), so a poll and a foreground trigger cannot
/// double-download.
public final class OtaUpdater {

    /// One check cycle's terminal standing (the funnel's own vocabulary).
    public struct OtaStanding: Equatable {

        public enum Kind: Equatable {
            case upToDate
            case updateAvailable
            case transportFailure
            case protocolFailure
            case downloadFailed
            case checksumMismatch
            case signatureInvalid
            case applyError
            case applyRefused
            case applied
        }

        public let kind: Kind
        public let manifest: OtaManifest?

        init(kind: Kind, manifest: OtaManifest?) {
            self.kind = kind
            self.manifest = manifest
        }
    }

    private let config: OtaConfig
    private let http: OtaHttp
    private let downloader: OtaDownloader
    private let verifier: OtaVerifier
    private let reporter: OtaReporter
    private let cycleLock = NSLock()
    private let stateLock = NSLock()
    private var pollSeconds: Int64
    private var lastManifest: OtaManifest?

    init(config: OtaConfig, http: OtaHttp, downloader: OtaDownloader,
         verifier: OtaVerifier, reporter: OtaReporter) {
        self.config = config
        self.http = http
        self.downloader = downloader
        self.verifier = verifier
        self.reporter = reporter
        self.pollSeconds = config.pollIntervalSeconds
    }

    /// Starts the updater: the SDK's one entry point.
    public static func start(config: OtaConfig) -> OtaUpdater {
        let http = URLSessionOtaHttp()
        return OtaUpdater(
            config: config,
            http: http,
            downloader: OtaDownloader(http: http, directory: config.downloadDirectory),
            verifier: OtaVerifier(verificationKeys: config.verificationKeys),
            reporter: OtaReporter(config: config, http: http))
    }

    /// One check alone: the standing and the served manifest
    /// (transport failures answer `transportFailure`, never nil).
    public func checkNow() -> OtaStanding {
        let response: OtaHttpResponse
        do {
            response = try http.get(url: config.checkUrl + "?" + config.checkQuery,
                                    bearerToken: config.appToken)
        } catch {
            return OtaStanding(kind: .transportFailure, manifest: nil)
        }
        switch response.status {
        case 204:
            stateLock.lock()
            lastManifest = nil
            stateLock.unlock()
            return OtaStanding(kind: .upToDate, manifest: nil)
        case 200 where response.body != nil:
            guard let manifest = OtaManifest.parse(response.body!) else {
                return OtaStanding(kind: .protocolFailure, manifest: nil)
            }
            stateLock.lock()
            pollSeconds = manifest.pollIntervalSeconds
            lastManifest = manifest
            stateLock.unlock()
            reporter.onManifest(manifest)
            seenQuietly()
            return OtaStanding(kind: .updateAvailable, manifest: manifest)
        default:
            return OtaStanding(kind: .protocolFailure, manifest: nil)
        }
    }

    /// One complete update cycle: check, download, verify, apply,
    /// report, flush. The standing is the answer — every leg's
    /// outcome rode the lifecycle queue on the way.
    public func updateNow(applier: BundleApplier) -> OtaStanding {
        cycleLock.lock(); defer { cycleLock.unlock() }
        let standing = checkNow()
        guard let manifest = standing.manifest else { return standing }
        guard let bundle = downloader.download(manifest) else {
            reporter.downloadFailed()
            flushQuietly()
            return OtaStanding(kind: .downloadFailed, manifest: manifest)
        }
        reporter.downloadSucceeded()
        switch verifier.verify(manifest: manifest, bundle: bundle) {
        case .verified:
            break
        case .checksumMismatch:
            reporter.failedChecksumMismatch()
            downloader.cleanup(manifest)
            flushQuietly()
            return OtaStanding(kind: .checksumMismatch, manifest: manifest)
        case .signatureInvalid:
            reporter.failedSignatureInvalid()
            downloader.cleanup(manifest)
            flushQuietly()
            return OtaStanding(kind: .signatureInvalid, manifest: manifest)
        }
        let applied: ApplyResult
        do {
            applied = try applier.apply(manifest, bundle: bundle)
        } catch {
            reporter.failedApplyError()
            flushQuietly()
            return OtaStanding(kind: .applyError, manifest: manifest)
        }
        switch applied {
        case .applied:
            reporter.applied()
            downloader.cleanup(manifest)
            flushQuietly()
            return OtaStanding(kind: .applied, manifest: manifest)
        case .refused:
            reporter.applyRefused()
            flushQuietly()
            return OtaStanding(kind: .applyRefused, manifest: manifest)
        }
    }

    /// The app's post-apply rollback verdict (resultless by shape,
    /// reported through the bound grant).
    public func reportRolledBack(detail: String? = nil) {
        reporter.rolledBack(detail: detail)
        flushQuietly()
    }

    /// The poll cadence: the last manifest's server-owned seconds,
    /// the config's fallback before one arrives.
    public func nextPollSeconds() -> Int64 {
        stateLock.lock(); defer { stateLock.unlock() }
        return pollSeconds
    }

    /// The last served manifest (the host's own forced/minAppVersion
    /// handling).
    public var currentManifest: OtaManifest? {
        stateLock.lock(); defer { stateLock.unlock() }
        return lastManifest
    }

    /// The queue's depth (the host surfaces it in debug builds).
    public var pendingEvents: Int { reporter.pendingEvents }

    /// Flushes the lifecycle queue; answers accepted batches (0
    /// without a bound grant).
    @discardableResult
    public func flush() -> Int { reporter.flush() }

    /// The seen report can never decide the check's answer: its
    /// enqueue is guarded by construction (the reporter's queue
    /// swallows its own persist failures), so a report that cannot
    /// reach the disk drops one queued word — the #174 review's
    /// medium finding, folded here from the start.
    private func seenQuietly() {
        reporter.seen()
    }

    /// A flush that cannot persist drops nothing the poll's next
    /// attempt will not re-derive; the cycle's answer never depends
    /// on the report leg.
    private func flushQuietly() {
        reporter.flush()
    }
}
