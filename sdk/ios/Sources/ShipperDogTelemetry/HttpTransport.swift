import Foundation

/// The transport seam (issue #72, S15-01): one POST of one batch
/// body. Injectable so the test suite proves the flush policy
/// against a fake; the production implementation rides URLSession —
/// Foundation only, the zero-dependency doctrine.
protocol HttpTransport: AnyObject {

    /// POSTs the body as JSON with the token's bearer header.
    /// Answers the HTTP status, or throws on transport failure (the
    /// flush loop's keep-queued signal).
    func post(url: String, bearerToken: String, jsonBody: String) throws -> Int
}

/// The production transport: one URLSession the SDK owns, telemetry-
/// sized timeouts, no cookies, no caching of bodies.
final class URLSessionTransport: HttpTransport {

    private let session: URLSession

    init(session: URLSession = URLSession(configuration: .ephemeral)) {
        self.session = session
    }

    func post(url: String, bearerToken: String, jsonBody: String) throws -> Int {
        var request = URLRequest(url: URL(string: url)!)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = jsonBody.data(using: .utf8)
        var result: Result<Int, Error> = .failure(URLError(.badURL))
        let semaphore = DispatchSemaphore(value: 0)
        let task = session.dataTask(with: request) { _, response, error in
            if let error = error {
                result = .failure(error)
            } else if let http = response as? HTTPURLResponse {
                result = .success(http.statusCode)
            } else {
                result = .failure(URLError(.badServerResponse))
            }
            semaphore.signal()
        }
        task.resume()
        semaphore.wait()
        return try result.get()
    }
}
