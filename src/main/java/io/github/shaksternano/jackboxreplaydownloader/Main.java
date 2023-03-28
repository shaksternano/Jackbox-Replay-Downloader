package io.github.shaksternano.jackboxreplaydownloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Main {

    private static final int MAX_GIF_REQUEST_ATTEMPTS = 5;
    private static final int TIME_BETWEEN_GIF_REQUEST_ATTEMPTS = 5000;

    public static void main(String[] args) {
        var scanner = new Scanner(System.in);
        System.out.println("Input from URL (1) or from local storage JSON (2)? Default: 1");
        var input = scanner.nextLine();
        CompletableFuture<List<File>> future;
        System.out.println();
        if (input.equals("2")) {
            System.out.println("Input local storage JSON:");
            var localStorage = scanner.nextLine();
            future = downloadGifs(JsonParser.parseString(localStorage).getAsJsonArray());
        } else {
            System.out.println("Input URL:");
            var url = scanner.nextLine();
            future = downloadGifs(url);
        }
        System.out.println();
        System.out.println("Downloading...");
        var files = future.join();
        System.out.println();
        if (files.isEmpty()) {
            System.out.println("No files downloaded.");
        } else {
            System.out.println("Files downloaded:");
            files.stream()
                .map(File::getAbsolutePath)
                .forEach(System.out::println);
        }
    }

    private static CompletableFuture<List<File>> downloadGifs(JsonArray localStorage) {
        var urls = localStorage.asList()
            .stream()
            .filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .flatMap(jsonObject -> Optional.ofNullable(jsonObject.get("url"))
                .flatMap(Main::tryGetAsString)
                .stream()
            )
            .collect(Collectors.toSet());
        var futures = urls.stream()
            .map(Main::downloadGifs)
            .toList();
        return allOf(futures)
            .thenApply(lists -> lists.stream()
                .flatMap(Collection::stream)
                .toList()
            );
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static CompletableFuture<List<File>> downloadGifs(String artifactUrl) {
        var parts = artifactUrl.split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid URL");
        }
        var gameName = parts[parts.length - 2];
        var sessionId = parts[parts.length - 1];
        var directory = new File("output");
        directory.mkdirs();
        return retrieveGameObjectIds(gameName, sessionId)
            .thenApply(gameObjectIds -> gameObjectIds.stream()
                .map(gameObjectId -> downloadGif(gameName, sessionId, gameObjectId, directory))
                .toList()
            )
            .thenCompose(Main::allOf)
            .thenApply(gifFileOptionals -> gifFileOptionals.stream()
                .flatMap(Optional::stream)
                .toList()
            );
    }

    private static Optional<String> tryGetAsString(JsonElement jsonElement) {
        try {
            return Optional.of(jsonElement.getAsString());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static CompletableFuture<List<String>> retrieveGameObjectIds(String gameName, String sessionId) {
        var requestUrl = "https://fishery.jackboxgames.com/artifact/gallery/" + gameName + "/" + sessionId;
        var request = HttpRequest.newBuilder()
            .uri(URI.create(requestUrl))
            .build();
        return HttpClient.newHttpClient()
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> getGameObjectIds(response, gameName))
            .exceptionally(throwable -> List.of());
    }

    private static CompletableFuture<Optional<File>> downloadGif(String gameName, String sessionId, String gameObjectId, File directory) {
        return retrieveGifUrl(gameName, sessionId, gameObjectId)
            .thenApply(gifUrlOptional -> gifUrlOptional.map(gifUrl -> {
                try (var inputStream = new URL(gifUrl).openStream()) {
                    var gameObjectIdDashes = gameObjectId.replace("_", "-");
                    var file = new File(directory, gameName + "-" + sessionId + "-" + gameObjectIdDashes + ".gif");
                    var path = file.toPath();
                    Files.copy(inputStream, path, StandardCopyOption.REPLACE_EXISTING);
                    return file;
                } catch (Exception e) {
                    return null;
                }
            }));
    }

    private static CompletableFuture<Optional<String>> retrieveGifUrl(String gameName, String sessionId, String gameObjectId) {
        return retrieveGifUrl(gameName, sessionId, gameObjectId, MAX_GIF_REQUEST_ATTEMPTS);
    }

    private static <T> CompletableFuture<List<T>> allOf(Collection<CompletableFuture<T>> futures) {
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
            .thenApply($ -> futures.stream()
                .map(CompletableFuture::join)
                .toList()
            );
    }

    private static List<String> getGameObjectIds(HttpResponse<String> response, String gameName) {
        var body = JsonParser.parseString(response.body()).getAsJsonObject();
        var gameData = body.get("gameData");
        if (gameData != null && gameData.isJsonArray()) {
            return getGameObjectIds(gameData.getAsJsonArray(), gameName);
        } else {
            return List.of();
        }
    }

    private static List<String> getGameObjectIds(JsonArray gameData, String gameName) {
        if (gameName.equals("quiplash3Game")) {
            try {
                var roundCount = gameData.get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("blob")
                    .getAsJsonArray("matchups")
                    .size();
                return IntStream.range(0, roundCount)
                    .mapToObj(String::valueOf)
                    .toList();
            } catch (Exception ignored) {
            }
        }

        return gameData.asList()
            .stream()
            .filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .flatMap(Main::getGameObjectIds)
            .toList();
    }

    private static Stream<String> getGameObjectIds(JsonObject component) {
        try {
            var type = Optional.ofNullable(component.get("type"))
                .map(JsonElement::getAsString)
                .orElse("");
            return switch (type) {
                case "shareable" -> Optional.ofNullable(component.get("gameObjectId"))
                    .map(JsonElement::getAsString)
                    .stream();
                case "container" -> Optional.ofNullable(component.get("children"))
                    .filter(JsonElement::isJsonArray)
                    .map(JsonElement::getAsJsonArray)
                    .map(JsonArray::asList)
                    .stream()
                    .flatMap(List::stream)
                    .filter(JsonElement::isJsonObject)
                    .map(JsonElement::getAsJsonObject)
                    .flatMap(Main::getGameObjectIds);
                default -> Stream.empty();
            };
        } catch (Exception e) {
            return Stream.empty();
        }
    }

    private static CompletableFuture<Optional<String>> retrieveGifUrl(String gameName, String sessionId, String gameObjectId, int attemptsLeft) {
        return requestGif(gameName, sessionId, gameObjectId)
            .thenCompose(exists -> {
                if (exists) {
                    return CompletableFuture.completedFuture(Optional.of(gifUrl(gameName, sessionId, gameObjectId)));
                } else {
                    if (attemptsLeft > 0) {
                        try {
                            Thread.sleep(TIME_BETWEEN_GIF_REQUEST_ATTEMPTS);
                        } catch (Exception ignored) {
                        }
                        return retrieveGifUrl(gameName, sessionId, gameObjectId, attemptsLeft - 1);
                    } else {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                }
            });
    }

    private static CompletableFuture<Boolean> requestGif(String gameName, String sessionId, String gameObjectId) {
        var requestUrl = "https://fishery.jackboxgames.com/artifact/gif/" + gameName + "/" + sessionId + "/" + gameObjectId;
        var request = HttpRequest.newBuilder()
            .uri(URI.create(requestUrl))
            .build();
        return HttpClient.newHttpClient()
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> response.statusCode() == 200);
    }

    private static String gifUrl(String gameName, String sessionId, String gameObjectId) {
        return "https://s3.amazonaws.com/jbg-blobcast-artifacts/" + gameName + "/" + sessionId + "/anim_" + gameObjectId + ".gif";
    }
}
