package dev.kilo.aibots.skin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves Minecraft skins for bots. Accepts either a premium username or a raw
 * base64 texture value, and returns a {value, signature} pair ready for injection
 * into the bot's GameProfile. Results are cached in memory.
 */
public final class SkinResolver {

    private static final Map<String, String[]> CACHE = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Gson GSON = new Gson();

    private SkinResolver() {
    }

    /** Returns {value, signature} or null (default Steve/Alex). Blocking - run off the main thread. */
    public static String[] resolve(String input) {
        if (input == null || input.isBlank()) return null;
        input = input.trim();

        // raw base64 texture value passthrough
        if (input.length() > 200 && !input.contains(" ")) {
            return new String[]{input, null};
        }
        String[] cached = CACHE.get(input.toLowerCase());
        if (cached != null) return cached;
        try {
            // 1. username -> undashed profile id
            HttpRequest idReq = HttpRequest.newBuilder(
                            URI.create("https://api.mojang.com/users/profiles/minecraft/" + input))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> idResp = HTTP.send(idReq, HttpResponse.BodyHandlers.ofString());
            if (idResp.statusCode() != 200) return null;
            JsonObject profile = GSON.fromJson(idResp.body(), JsonObject.class);
            String id = profile.get("id").getAsString();

            // 2. profile id -> signed textures property
            HttpRequest texReq = HttpRequest.newBuilder(
                            URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false"))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> texResp = HTTP.send(texReq, HttpResponse.BodyHandlers.ofString());
            if (texResp.statusCode() != 200) return null;
            JsonObject full = GSON.fromJson(texResp.body(), JsonObject.class);
            JsonArray props = full.getAsJsonArray("properties");
            for (int i = 0; i < props.size(); i++) {
                JsonObject p = props.get(i).getAsJsonObject();
                if ("textures".equals(p.get("name").getAsString())) {
                    String[] out = new String[]{
                            p.get("value").getAsString(),
                            p.has("signature") ? p.get("signature").getAsString() : null};
                    CACHE.put(input.toLowerCase(), out);
                    return out;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
