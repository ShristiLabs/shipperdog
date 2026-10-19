import Foundation

/// One GET answer: the status code and, on 2xx, the decoded body.
struct OtaHttpResponse {
    let status: Int
    let body: String?
}

/// The transport seam of the OTA module (issue #175, S16-13): the
/// telemetry SDK's own injectable-seam doctrine. Three verbs only —
/// the authenticated GET the check presents, the range-capable GET
/// the downloader stages bytes with, and the batch POST the lifecycle
/// reporter flushes through. The production implementation rides
/// URLSession (Foundation only — the zero-dependency doctrine); the
/// XCTest suite proves every policy against fakes.
protocol OtaHttp: AnyObject {

    /// One authenticated GET: answers the status with the body, or
    /// throws on transport failure.
    func get(url: String, bearerToken: String) throws -> OtaHttpResponse

    /// Streams one GET into `target`, optionally from a byte offset
    /// (the resume leg's `Range: bytes=<from>-`). The STATUS decides
    /// the file mode — the graceful-degrade contract: a 206 answer
    /// APPENDS the requested tail to what is staged; a plain 200
    /// answer means the endpoint ignored the range and carries the
    /// WHOLE object, so the file is restaged from zero; any other
    /// answer leaves the file untouched. Answers the status.
    func download(url: String, rangeFromBytes: Int64?, target: URL) throws -> Int

    /// POSTs the JSON body with the bearer header; answers the status
    /// or throws on transport failure.
    func post(url: String, bearerToken: String, jsonBody: String) throws -> Int
}

/// The production transport: one ephemeral URLSession the SDK owns —
/// no cookies, no caching of bodies, update-sized timeouts. The
/// download verb rides a DOWNLOAD task (URLSession itself streams
/// bytes to a file it hands back; a staged bundle never sits in
/// memory), then applies the status's file mode from the seam's own
/// contract. Synchronous the way the telemetry transport is
/// (`URLSessionTransport`): the host called a synchronous funnel.
final class URLSessionOtaHttp: OtaHttp {

    private let session: URLSession

    init(session: URLSession = URLSession(configuration: .ephemeral)) {
        self.session = session
    }

    func get(url: String, bearerToken: String) throws -> OtaHttpResponse {
        var request = URLRequest(url: try Self.wireUrl(url))
        request.timeoutInterval = Self.requestTimeoutSeconds
        request.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        var outcome: Result<OtaHttpResponse, Error> = .failure(URLError(.badServerResponse))
        let semaphore = DispatchSemaphore(value: 0)
        let task = session.dataTask(with: request) { data, response, error in
            defer { semaphore.signal() }
            if let error {
                outcome = .failure(error)
            } else if let http = response as? HTTPURLResponse {
                let status = http.statusCode
                outcome = .success(OtaHttpResponse(
                    status: status,
                    body: (200...299).contains(status)
                        ? data.flatMap { String(data: $0, encoding: .utf8) }
                        : nil))
            } else {
                outcome = .failure(URLError(.badServerResponse))
            }
        }
        task.resume()
        semaphore.wait()
        return try outcome.get()
    }

    func download(url: String, rangeFromBytes: Int64?, target: URL) throws -> Int {
        var request = URLRequest(url: try Self.wireUrl(url))
        request.timeoutInterval = Self.downloadTimeoutSeconds
        if let rangeFromBytes, rangeFromBytes > 0 {
            request.setValue("bytes=\(rangeFromBytes)-", forHTTPHeaderField: "Range")
        }
        var outcome: Result<Int, Error> = .failure(URLError(.badServerResponse))
        let semaphore = DispatchSemaphore(value: 0)
        let task = session.downloadTask(with: request) { location, response, error in
            defer { semaphore.signal() }
            if let error {
                outcome = .failure(error)
                return
            }
            guard let http = response as? HTTPURLResponse else {
                outcome = .failure(URLError(.badServerResponse))
                return
            }
            let status = http.statusCode
            if (200...299).contains(status), let location {
                Self.stage(answer: location, status: status, target: target)
            }
            outcome = .success(status)
        }
        task.resume()
        semaphore.wait()
        return try outcome.get()
    }

    func post(url: String, bearerToken: String, jsonBody: String) throws -> Int {
        var request = URLRequest(url: try Self.wireUrl(url))
        request.timeoutInterval = Self.requestTimeoutSeconds
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = jsonBody.data(using: .utf8)
        var outcome: Result<Int, Error> = .failure(URLError(.badServerResponse))
        let semaphore = DispatchSemaphore(value: 0)
        let task = session.dataTask(with: request) { _, response, error in
            defer { semaphore.signal() }
            if let error {
                outcome = .failure(error)
            } else if let http = response as? HTTPURLResponse {
                outcome = .success(http.statusCode)
            } else {
                outcome = .failure(URLError(.badServerResponse))
            }
        }
        task.resume()
        semaphore.wait()
        return try outcome.get()
    }

    /// Wire URLs are DATA, never shape to trust: the manifest's own
    /// `url` arrives off the network, so a malformed value must
    /// answer a THROWN error — the failure standing the Kotlin
    /// sibling's transport serves (`java.net.URL`'s own
    /// MalformedURLException leg), never the force-unwrap crash the
    /// review's high finding caught. The one construction seam all
    /// three verbs share; internal so the XCTest suite pins it.
    ///
    /// The guard pins the INVARIANT, not the parser's mood: the
    /// pre-macOS-13/iOS-16 parser rejects outright what the current
    /// one percent-encodes into a scheme-less relative reference, so
    /// "parseable" alone is version-dependent — no URL, or no
    /// http(s) scheme, is a bad-URL error on every OS the platform
    /// floor covers. (A parseable http(s) URL with a broken host
    /// still answers URLSession's own transport error, which the
    /// funnel already reports as a standing.)
    static func wireUrl(_ raw: String) throws -> URL {
        guard let url = URL(string: raw),
              url.scheme == "http" || url.scheme == "https" else {
            throw URLError(.badURL)
        }
        return url
    }

    /// Applies the seam's file mode to the answer URLSession staged
    /// on disk: the 206 tail APPENDS in chunks (a bundle never sits
    /// in memory), the plain-200 whole object RESTAGES from zero.
    private static func stage(answer: URL, status: Int, target: URL) {
        try? FileManager.default.createDirectory(at: target.deletingLastPathComponent(),
                                                 withIntermediateDirectories: true)
        if status == 206 {
            if !FileManager.default.fileExists(atPath: target.path) {
                FileManager.default.createFile(atPath: target.path, contents: nil)
            }
            guard let source = try? FileHandle(forReadingFrom: answer),
                  let sink = try? FileHandle(forWritingTo: target) else {
                return
            }
            defer { source.closeFile(); sink.closeFile() }
            _ = sink.seekToEndOfFile()
            while true {
                let chunk = source.readData(ofLength: Self.stageChunkBytes)
                if chunk.isEmpty {
                    break
                }
                sink.write(chunk)
            }
        } else {
            try? FileManager.default.removeItem(at: target)
            try? FileManager.default.moveItem(at: answer, to: target)
        }
    }

    private static let requestTimeoutSeconds: TimeInterval = 30

    /// Bundle-sized, not telemetry-sized: a staged download
    /// legitimately runs long (the Kotlin sibling's 120 s read bound).
    private static let downloadTimeoutSeconds: TimeInterval = 120
    private static let stageChunkBytes = 65_536
}
