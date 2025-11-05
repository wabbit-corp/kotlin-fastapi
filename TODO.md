## Common
* [ ] Unix domain sockets / named pipes (local IPC)
* [ ] Add tests to check serialization of:
    * [ ] `Array<T>`
    * [ ] `List<T>`
    * [ ] `Set<T>`
    * [ ] `Map<K, V>`
    * [ ] `IntArray`, `LongArray`, `FloatArray`, `DoubleArray`,`BooleanArray`, `CharArray`

## CLI
* [ ] Wire up `@Cli.Help` on functions and parameters so `--help` surfaces those texts.
* [ ] Define and support `@Cli.Example`, and render examples in `--help`.
* [ ] Enrich `--help` to include default values for flags/options and parameters.
* [ ] Enrich `--help` to list valid enum values for enum-typed params/positionals.
* [ ] Accept short-option equals form (`-o=1`) in addition to `--opt=1` and `-o 1`.
* [ ] Offer “did you mean …?” suggestions for unknown long/short options.
* [ ] Detect missing required options and report which one(s), including their help text.

## HTTP server
* [ ] Generate OpenAPI schema for Http server.
* [ ] Test ports: don’t hardcode 8080 in tests; bind port 0 and read the assigned port.
* [ ] HTTP CORS defaults: anyHost() + allowCredentials = true is a browser‑reject combo. Either set explicit hosts or disable credentials by default.
* [ ] Query/path name overrides: A way to say “bind this parameter to query key X” and (optionally) “bind to path variable X”, e.g. `@Query("x") x: Int`, `@Path("y") y: String`.
* [ ] Multipart: Support `@Part` annotation to name parts, and support file uploads.
* [ ] Nullable query params should be optional. Currently we 400 on missing.
* [ ] Default values (e.g., `listLoginHistory(limit: Int = 50)`) should work by omitting the arg in `callBy` when not present (check `KParameter.isOptional`) instead of forcing a value/400.
* [ ] coerceFromString fallback currently uses `Json.decodeFromString(serializer, raw)` without quotes. Test it with inline classes, enums, etc. If the serializer’s descriptor is `PrimitiveKind.STRING` or `SerialKind.ENUM`, decode as a JSON string: `Json.decodeFromString(s, "\"$raw\"")`. Do not rely on `toString()` for inline value classes.
* [ ] Add `data class HttpMeta<Result>(val status: Int? = null, val headers: Map<String, String> = emptyMap(), val result: Result)` and handle it specially in Http client and server to set status/headers.
* [ ] On unauthorized due to missing `@Bearer` → return `401` + `WWW-Authenticate`.
* [ ] WebRTC data channels?
* [ ] QUIC / HTTP‑3?
* [ ] Interceptors (client & server): retries, circuit‑breakers, rate limits, auth injectors, idempotency keys.
* [ ] Caching: already have `@Cache`. Extend to server‑side ETags + conditional requests + client memoization layer.
* [ ] Multipart + streaming bodies: `@Upload` returns `Flow<ByteArray>`; server responds with checksum, size, ETag. Mirror for download.
* [ ] NDJSON / Record streams: negotiated by `Accept`. Useful for large queries with early results.
* [ ] GraphQL?
* [ ] gRPC?
