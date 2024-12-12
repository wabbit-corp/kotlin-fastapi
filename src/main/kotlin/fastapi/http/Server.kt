//package fastapi.http
//
//import exceptionjson.throwableToJson
//import io.ktor.http.*
//import io.ktor.serialization.kotlinx.json.*
//import io.ktor.server.application.*
//import io.ktor.server.engine.*
//import io.ktor.server.netty.*
//import io.ktor.server.plugins.callloging.*
//import io.ktor.server.plugins.contentnegotiation.*
//import io.ktor.server.plugins.cors.routing.*
//import io.ktor.server.plugins.statuspages.*
//import io.ktor.server.response.*
//import io.ktor.server.routing.*
//import kotlinx.serialization.KSerializer
//import kotlinx.serialization.json.Json
//import kotlinx.serialization.serializer
//import org.slf4j.LoggerFactory
//import org.slf4j.event.Level
//import kotlin.reflect.KClass
//
//inline fun <reified T1 : Any> server(
//    t1: T1, host: String = "0.0.0.0", port: Int = 8080
//) {
//    unsafeServer(listOf(t1 to t1::class), host, port)
//}
//
//inline fun <reified T1 : Any, reified T2 : Any> server(
//    t1: T1, t2: T2, host: String = "0.0.0.0", port: Int = 8080
//) {
//    unsafeServer(listOf(t1 to t1::class, t2 to t2::class), host, port)
//}
//
//inline fun <reified T1 : Any, reified T2 : Any, reified T3 : Any> server(
//    t1: T1, t2: T2, t3: T3, host: String = "0.0.0.0", port: Int = 8080
//) {
//    unsafeServer(listOf(t1 to t1::class, t2 to t2::class, t3 to t3::class), host, port)
//}
//
//fun unsafeServer(
//    kClasses: List<Pair<Any, KClass<*>>>,
//    host: String = "0.0.0.0", port: Int = 8080
//) {
//    for ((impl, kClass) in kClasses) {
//        require(kClass.isInstance(impl)) { "Implementation $impl is not an instance of $kClass" }
//    }
//    val interfaces = parseKClasses(kClasses.map { it.second }).list
//
//    val log = LoggerFactory.getLogger("ktor.application")
//
//    val env = applicationEngineEnvironment {
//        this.log = log
//
//        module {
//            install(CallLogging) { level = Level.INFO }
//
//            install(StatusPages) {
//                exception<Throwable> { call, ex ->
//                    call.respond(
//                        HttpStatusCode.InternalServerError,
//                        throwableToJson(ex)
//                    )
//                    throw ex
//                }
//            }
//
//            install(CORS) {
//                anyHost()
//                allowMethod(HttpMethod.Options)
//                allowMethod(HttpMethod.Get)
//                allowMethod(HttpMethod.Post)
//                allowMethod(HttpMethod.Put)
//                allowMethod(HttpMethod.Delete)
//                allowMethod(HttpMethod.Patch)
//                allowHeader(HttpHeaders.AccessControlAllowHeaders)
//                allowHeader(HttpHeaders.ContentType)
//                allowHeader(HttpHeaders.Authorization)
//                allowHeader(HttpHeaders.AccessControlAllowOrigin)
//                allowNonSimpleContentTypes = true
//                allowCredentials = true
//                allowSameOrigin = true
//            }
//
//            install(ContentNegotiation){
//                json(Json {
//                    prettyPrint = false
//                    isLenient = true
//                })
//            }
//
//            routing {
//                for (_interface in interfaces) {
//                    for (method in _interface.list) {
//                        val path = method.httpPath
//                        val httpMethod = method.httpMethod
//                        val bodyParameter = method.bodyParameter
//                        val resultDeserializer = method.resultDeserializer
//
//                        route(method.originalHttpPath, method.httpMethod) {
//                            handle {
//                                val implArgs = mutableListOf<Any?>()
//                                for (arg in method.httpPath) {
//                                    if (arg !is PathChunk.Arg) continue
//                                    val value = call.parameters[arg.name]
//                                    val serializer = arg.serializer
//                                    val implArg = Json.decodeFromString(serializer, value)
//                                    implArgs.add(implArg)
//                                }
//
//                                val implResult = implMethod.invoke(impl, *implArgs.toTypedArray())
//
//                                val result = Json.encodeToString(resultDeserializer.serializer, implResult)
//                                call.respondText(result, ContentType.Application.Json)
//                            }
//                        }
//                    }
//                }
//            }
//        }
//
//        connector {
//            this.host = host
//            this.port = port
//        }
//    }
//
//    val ktorServer = embeddedServer(Netty, env)
//    ktorServer.start(wait = true)
//}
//
