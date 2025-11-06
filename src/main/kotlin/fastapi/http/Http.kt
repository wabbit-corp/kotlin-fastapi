package fastapi.http

object Http {
    annotation class Cache(val client: Double)

    annotation class Get(val name: String)

    annotation class Post(val name: String)

    annotation class Put(val name: String)

    annotation class Delete(val name: String)

    annotation class Patch(val name: String)

    annotation class Head(val name: String)

    annotation class Options(val name: String)

    annotation class JsonBody

    /**
     * Marks a streaming RPC.
     * - Return type Flow<T> with no streaming parameter => SSE (server-push)
     * - Presence of a Flow/Channel parameter => WebSocket (bidirectional)
     */
    annotation class Stream

    /** Bind a method parameter to a header (server: read from request, client: set on request) */
    @Target(AnnotationTarget.VALUE_PARAMETER) annotation class Header(val name: String)

    /** Sugar for Authorization: Bearer <token> */
    @Target(AnnotationTarget.VALUE_PARAMETER) annotation class Bearer
}
