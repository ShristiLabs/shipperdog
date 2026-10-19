import Foundation

/// The resumable bundle downloader (issue #175, S16-13): stream-to-
/// file staging under the config's download directory —
/// `<bundleId>.part` while in flight, atomically renamed to
/// `<bundleId>.bundle` once the staged size matches the manifest's
/// `sizeBytes` (when it names one).
///
/// Resume is HTTP Range, opportunistically: a `.part` file present
/// asks the download URL for `bytes=<staged>-`. A 206 answer appends;
/// a plain 200 answer means the endpoint ignored the range (the
/// compose download proxy streams whole objects; production's signed
/// GCS URLs answer 206) — the downloader degrades gracefully by
/// restaging from zero rather than failing. A 416 with a staged
/// `.part` is completion, not failure.
public final class OtaDownloader {

    private let http: OtaHttp
    private let directory: URL?
    private let lock = NSLock()
    private var fallback: URL?

    init(http: OtaHttp, directory: URL?) {
        self.http = http
        self.directory = directory
    }

    /// The staged, size-checked bundle file — or nil when the
    /// download failed (the caller reports OTA_DOWNLOAD FAILED).
    public func download(_ manifest: OtaManifest) -> URL? {
        let staging = stagingDirectory()
        try? FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        let part = staging.appendingPathComponent(manifest.bundleId + OtaDownloader.partSuffix)
        let staged = OtaDownloader.size(of: part)
        let status: Int
        do {
            status = try http.download(url: manifest.url,
                                       rangeFromBytes: staged > 0 ? staged : nil,
                                       target: part)
        } catch {
            return nil
        }
        switch status {
        case 200, 206:
            return settle(manifest: manifest, part: part)
        case 416 where OtaDownloader.size(of: part) > 0:
            return settle(manifest: manifest, part: part)
        default:
            return nil
        }
    }

    /// Drops one bundle's staged bytes (the completed update's cleanup).
    public func cleanup(_ manifest: OtaManifest) {
        let staging = stagingDirectory()
        try? FileManager.default.removeItem(at: staging.appendingPathComponent(manifest.bundleId + OtaDownloader.partSuffix))
        try? FileManager.default.removeItem(at: staging.appendingPathComponent(manifest.bundleId + OtaDownloader.bundleSuffix))
    }

    private func settle(manifest: OtaManifest, part: URL) -> URL? {
        if let sizeBytes = manifest.sizeBytes, OtaDownloader.size(of: part) != sizeBytes {
            return nil
        }
        let bundle = stagingDirectory().appendingPathComponent(manifest.bundleId + OtaDownloader.bundleSuffix)
        // The atomic rename: remove-then-move (a same-name re-download
        // replaces the settled file, never appends beside it).
        try? FileManager.default.removeItem(at: bundle)
        do {
            try FileManager.default.moveItem(at: part, to: bundle)
        } catch {
            return nil
        }
        return bundle
    }

    private func stagingDirectory() -> URL {
        if let directory {
            return directory
        }
        lock.lock(); defer { lock.unlock() }
        if let fallback {
            return fallback
        }
        let fresh = FileManager.default.temporaryDirectory
            .appendingPathComponent("sd-ota-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: fresh, withIntermediateDirectories: true)
        fallback = fresh
        return fresh
    }

    private static func size(of url: URL) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.int64Value ?? 0
    }

    /// The in-flight staging suffix.
    public static let partSuffix = ".part"

    /// The settled, verified-bundle suffix.
    public static let bundleSuffix = ".bundle"
}
