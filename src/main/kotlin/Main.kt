package io.github.shaksternano.jackboxreplaydownloader

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

fun main() = runBlocking {
    println("Input from URL (1) or from local storage JSON (2)? Default: 1")
    val choice = readln().trim()
    println()
    val result: Deferred<List<Path>>
    if (choice == "2") {
        println("Input local storage JSON:")
        val localStorage = readln().trim()
        println()
        val parsed: JsonArray
        try {
            parsed = Json.parseToJsonElement(localStorage) as JsonArray
        } catch (e: Exception) {
            println("Invalid local storage JSON")
            return@runBlocking
        }
        result = downloadGifs(parsed, this)
    } else {
        println("Input URL:")
        val url = readln().trim()
        println()
        result = downloadGifs(url, this) ?: return@runBlocking println("Invalid URL: $url")
    }
    println("Downloading...")
    val paths = result.await()
    println()
    if (paths.isEmpty()) println("No files downloaded")
    else {
        println("Files downloaded:")
        paths
            .map(Path::absolute)
            .forEach(::println)
    }
}

suspend fun downloadGifs(localStorage: JsonArray, scope: CoroutineScope): Deferred<List<Path>> = scope.async {
    localStorage
        .filterIsInstance<JsonObject>()
        .mapNotNull { it["url"]?.toString() }
        .distinct()
        .mapNotNull { downloadGifs(it, this) }
        .let { allOf(it, this).await() }
        .flatten()
}

suspend fun downloadGifs(artifactUrl: String, scope: CoroutineScope): Deferred<List<Path>>? {
    val (gameName, sessionId) = parseArtifactUrl(artifactUrl) ?: return null
    val directory = Path.of("output")
    directory.createDirectories()
    return scope.async {
        val gameObjectIds = retrieveGameObjectIds(gameName, sessionId)
        val deferredGifs = gameObjectIds
            .map {
                async {
                    downloadGif(gameName, sessionId, it, directory)
                }
            }
        allOf(deferredGifs, this).await()
            .filterNotNull()
    }
}

fun parseArtifactUrl(artifactUrl: String): Pair<String, String>? {
    val parts = artifactUrl.split("/")
    val artifactIndex = parts.indexOf("artifact")
    val gameName = parts.getOrElse(artifactIndex + 1) { return null }
    val sessionId = parts.getOrElse(artifactIndex + 2) { return null }
    return gameName to sessionId
}

suspend fun retrieveGameObjectIds(gameName: String, sessionId: String): List<String> {
    val requestUrl = "https://fishery.jackboxgames.com/artifact/gallery/$gameName/$sessionId"
    val response = HttpClient().get(requestUrl)
    val body = Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
    return body?.let { getGameObjectIds(it, gameName) } ?: emptyList()
}

fun getGameObjectIds(responseBody: JsonObject, gameName: String): List<String> {
    val gameData = responseBody["gameData"] as? JsonArray
    return gameData?.let { getGameObjectIds(it, gameName) } ?: emptyList()
}

fun getGameObjectIds(gameData: JsonArray, gameName: String): List<String> {
    if (gameName == "quiplash3Game") {
        val gameDataElement = gameData.firstOrNull() as? JsonObject
        val blob = gameDataElement?.get("blob") as? JsonObject
        val matchUps = blob?.get("matchups") as? JsonArray
        val roundCount = matchUps?.size
        if (roundCount != null) {
            return (0 until roundCount).map(Any::toString)
        }
    }
    return gameData
        .filterIsInstance<JsonObject>()
        .flatMap { getGameObjectIds(it) }
}

fun getGameObjectIds(component: JsonObject): List<String> = when (component["type"]?.toString() ?: "") {
    "shareable" -> listOfNotNull(component["gameObjectId"]).map(Any::toString)
    "container" -> listOfNotNull(component["children"])
        .filterIsInstance<JsonArray>()
        .flatten()
        .filterIsInstance<JsonObject>()
        .flatMap { getGameObjectIds(it) }

    else -> emptyList()
}

suspend fun downloadGif(gameName: String, sessionId: String, gameObjectId: String, directory: Path): Path? {
    val gifUrl = retrieveGifUrl(gameName, sessionId, gameObjectId) ?: return null
    val gameObjectIdDashes = gameObjectId.replace("_", "-")
    val path = directory.resolve("$gameName-$sessionId-$gameObjectIdDashes.gif")
    withContext(Dispatchers.IO) {
        URL(gifUrl).openStream()
    }.use { input ->
        path.outputStream().use {
            input.copyTo(it)
        }
    }
    return path
}

suspend fun retrieveGifUrl(gameName: String, sessionId: String, gameObjectId: String): String? =
    if (requestGif(gameName, sessionId, gameObjectId))
        gifUrl(gameName, sessionId, gameObjectId)
    else null

suspend fun requestGif(gameName: String, sessionId: String, gameObjectId: String): Boolean {
    val requestUrl = "https://fishery.jackboxgames.com/artifact/gif/$gameName/$sessionId/$gameObjectId"
    val client = HttpClient {
        install(HttpRequestRetry) {
            maxRetries = 5
            retryIf { _, response ->
                !isOk(response)
            }
            delayMillis {
                it * 5000L
            }
        }
    }
    val response = client.get(requestUrl)
    return isOk(response)
}

fun isOk(response: HttpResponse): Boolean =
    response.status.value == 200

fun gifUrl(gameName: String, sessionId: String, gameObjectId: String): String =
    "https://s3.amazonaws.com/jbg-blobcast-artifacts/$gameName/$sessionId/anim_$gameObjectId.gif"

suspend fun <T> allOf(deferreds: Iterable<Deferred<T>>, scope: CoroutineScope): Deferred<List<T>> = scope.async {
    deferreds.map { it.await() }
}
