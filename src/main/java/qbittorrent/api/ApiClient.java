package qbittorrent.api;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qbittorrent.api.model.MainData;
import qbittorrent.api.model.Preferences;
import qbittorrent.api.model.Torrent;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class ApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiClient.class);

    private final String baseUrl;
    private final HttpClient client;
    private final Gson gson;
    private boolean loggedIn = false;
    private String authCookie;

    public ApiClient(final String baseUrl) {
        this.baseUrl = baseUrl;
        LOGGER.info("Using qBittorrent url {}", baseUrl);
        client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        gson = new Gson();
    }

    public void login(String username, String password) {
        final String url = baseUrl + "/api/v2/auth/login";
        LOGGER.info("Logging in user {} using {}", username, url);
        final String data = Map.of("username", username, "password", password)
            .entrySet()
            .stream()
            .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));

        final HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString(data))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build();

        try {
            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            final String body = response.body();
            final int status = response.statusCode();
            if (status != 200) {
                LOGGER.warn("Login Error: {}", body);
                throw new ApiException("Could not log in: (" + status + ") " + body);
            }

            final HttpHeaders headers = response.headers();
            final Optional<String> setCookie = headers.firstValue("Set-Cookie");

            if (setCookie.isEmpty()) {
                throw new ApiException("Could not get auth cookie from qBittorrent");
            }

            final String setCookieValue = setCookie.get();
            if (!setCookieValue.contains("SID=")) {
                throw new ApiException("Could not get auth cookie from qBittorrent");
            }

            authCookie = setCookie.get().split(";")[0].split("=")[1];
            loggedIn = true;
        } catch (IOException e) {
            throw new ApiException("Could not login", e);
        } catch (InterruptedException e) {
            LOGGER.error("Thread was interrupted while attempting to log in", e);
            Thread.currentThread().interrupt();
        }
    }

    public MainData getMainData() {
        return getRequest("/sync/maindata", new TypeToken<>() {
        });
    }

    public Preferences getPreferences() {
        return getRequest("/app/preferences", new TypeToken<>() {
        });
    }

    public String getVersion() {
        return getRequest("/app/version");
    }

    public List<Torrent> getTorrents() {
        return getRequest("/torrents/info", new TypeToken<>() {
        });
    }

    private String getRequest(final String apiUrl) {
        LOGGER.info("Making request to {}...", apiUrl);
        final String url = baseUrl + "/api/v2" + apiUrl;
        if (!loggedIn) {
            throw new ApiException("You must log in to retrieve torrents");
        }

        final HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Cookie", "SID=" + authCookie)
            .GET()
            .build();

        try {
            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            final int statusCode = response.statusCode();
            LOGGER.info("Response from {} endpoint was {}.", apiUrl, statusCode);
            if (statusCode != 200) {
                throw new ApiException("An error occurred calling " + url + ": (" + statusCode + ") " + response.body());
            }
            final String body = response.body();
            LOGGER.trace("JSON result from {} call: {}", apiUrl, body);
            return body;
        } catch (IOException e) {
            throw new ApiException("Could not make GET request to " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Thread was interrupted while making GET request to " + url, e);
        }
    }

    private <T> T getRequest(String apiUrl, TypeToken<T> token) {
        String json = getRequest(apiUrl);
        return gson.fromJson(json, token.getType());
    }
}
