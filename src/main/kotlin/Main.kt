package io.github.shaksternano.jackboxreplaydownloader

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.use

suspend fun main() {
    println("Input from URL (1) or from local storage JSON (2)? Default: 1")
    val choice = readln().trim()
    println()
    val paths = if (choice == "2") {
        println("Input local storage JSON:")
        val localStorage = readln().trim()
        println()
        val parsed = try {
            Json.parseToJsonElement(localStorage) as JsonArray
        } catch (e: Exception) {
            println("Invalid local storage JSON")
            return
        }
        println("Downloading...")
        downloadGifs(parsed)
    } else {
        println("Input URL:")
        val url = readln().trim()
        println()
        println("Downloading...")
        downloadGifs(url) ?: return println("Invalid URL: $url")
    }
    println()
    if (paths.isEmpty()) {
        println("No files downloaded")
    } else {
        println("${paths.size} files downloaded:")
        paths.map(Path::absolute).forEach(::println)
    }
}

suspend fun downloadGifs(localStorage: JsonArray): List<Path> {
    return localStorage
        .filterIsInstance<JsonObject>()
        .mapNotNull { getStringValue(it, "url") }
        .distinct()
        .parallelMap { downloadGifs(it) }
        .filterNotNull()
        .flatten()
}

fun getStringValue(json: JsonObject, key: String): String? {
    val value = json[key]
    return if (value is JsonPrimitive) {
        value.content
    } else {
        value?.toString()
    }
}

suspend fun <T, R> Iterable<T>.parallelMap(transform: suspend (T) -> R): List<R> = coroutineScope {
    map { async { transform(it) } }.awaitAll()
}

suspend fun downloadGifs(artifactUrl: String): List<Path>? {
    val (gameName, sessionId) = parseArtifactUrl(artifactUrl) ?: return null
    val directory = Path.of("output").createDirectories()
    return HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
        }
        install(HttpRequestRetry) {
            maxRetries = 5
            retryIf { _, response ->
                !isOk(response)
            }
            delayMillis {
                it * 5000L
            }
        }
    }.use { client ->
        retrieveGameObjectIds(gameName, sessionId, client)
            .parallelMap { downloadGif(gameName, sessionId, it, directory, client) }
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

suspend fun retrieveGameObjectIds(gameName: String, sessionId: String, client: HttpClient): List<String> {
    val requestUrl = if (gameName == "Quiplash2Game") {
        "https://fishery.jackboxgames.com/artifact/$gameName/$sessionId"
    } else {
        "https://fishery.jackboxgames.com/artifact/gallery/$gameName/$sessionId"
    }
    return try {
        val response = client.get(requestUrl)
        val body = response.body<JsonElement>() as? JsonObject
        body?.let { getGameObjectIds(it, gameName) } ?: emptyList()
    } catch (e: Exception) {
        println("An error occurred while retrieving data from $requestUrl")
        e.printStack()
        emptyList()
    }
}

fun getGameObjectIds(responseBody: JsonObject, gameName: String): List<String> {
    if (gameName == "Quiplash2Game") {
        val matchUps = responseBody["matchups"] as? JsonArray
        val roundCount = matchUps?.size
        if (roundCount != null) {
            return (0..<roundCount).map(Any::toString)
        }
    }
    val gameData = responseBody["gameData"]
    if (gameData is JsonArray) {
        if (gameName == "quiplash3Game") {
            val gameDataElement = gameData.firstOrNull() as? JsonObject
            val blob = gameDataElement?.get("blob") as? JsonObject
            val matchUps = blob?.get("matchups") as? JsonArray
            val roundCount = matchUps?.size
            if (roundCount != null) {
                return (0..<roundCount).map(Any::toString)
            }
        }
        return gameData
            .filterIsInstance<JsonObject>()
            .flatMap { getGameObjectIds(it) }
    }
    return emptyList()
}

fun getGameObjectIds(component: JsonObject): List<String> = when (getStringValue(component, "type")) {
    "shareable" -> listOfNotNull(getStringValue(component, "gameObjectId"))
    "container" -> listOfNotNull(component["children"])
        .filterIsInstance<JsonArray>()
        .flatten()
        .filterIsInstance<JsonObject>()
        .flatMap { getGameObjectIds(it) }

    else -> emptyList()
}

suspend fun downloadGif(
    gameName: String,
    sessionId: String,
    gameObjectId: String,
    directory: Path,
    client: HttpClient
): Path? {
    val gifUrl = retrieveGifUrl(gameName, sessionId, gameObjectId, client) ?: return null
    val gameObjectIdDashes = gameObjectId.replace("_", "-")
    val path = directory.resolve("$gameName-$sessionId-$gameObjectIdDashes.gif")
    return try {
        download(gifUrl, path, client)
        path
    } catch (e: Exception) {
        println("An error occurred while retrieving data from $gifUrl")
        e.printStack()
        null
    }
}

suspend fun retrieveGifUrl(gameName: String, sessionId: String, gameObjectId: String, client: HttpClient): String? =
    if (requestGif(gameName, sessionId, gameObjectId, client))
        gifUrl(gameName, sessionId, gameObjectId)
    else null

suspend fun requestGif(gameName: String, sessionId: String, gameObjectId: String, client: HttpClient): Boolean {
    val requestUrl = "https://fishery.jackboxgames.com/artifact/gif/$gameName/$sessionId/$gameObjectId"
    return try {
        val response = client.get(requestUrl)
        isOk(response)
    } catch (e: Exception) {
        println("An error occurred while requesting $requestUrl")
        e.printStack()
        false
    }
}

fun isOk(response: HttpResponse): Boolean =
    response.status.value == 200

fun gifUrl(gameName: String, sessionId: String, gameObjectId: String): String =
    "https://s3.amazonaws.com/jbg-blobcast-artifacts/$gameName/$sessionId/anim_$gameObjectId.gif"

suspend fun download(url: String, path: Path, client: HttpClient) {
    val response = client.get(url)
    val channel = response.bodyAsChannel()
    while (!channel.isClosedForRead) {
        val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
        while (!packet.isEmpty) {
            val bytes = packet.readBytes()
            withContext(Dispatchers.IO) {
                path.writeBytes(bytes, StandardOpenOption.APPEND, StandardOpenOption.CREATE)
            }
        }
    }
}
