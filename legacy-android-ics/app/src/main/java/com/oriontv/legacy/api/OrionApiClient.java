package com.oriontv.legacy.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.oriontv.legacy.api.models.ApiSite;
import com.oriontv.legacy.api.models.DoubanResponse;
import com.oriontv.legacy.api.models.Favorite;
import com.oriontv.legacy.api.models.LoginResult;
import com.oriontv.legacy.api.models.MutationResult;
import com.oriontv.legacy.api.models.PlayRecord;
import com.oriontv.legacy.api.models.SearchResponse;
import com.oriontv.legacy.api.models.ServerConfig;
import com.oriontv.legacy.api.models.VideoDetail;
import com.oriontv.legacy.data.PreferencesStore;
import com.oriontv.legacy.net.LegacyHttpCompat;
import com.oriontv.legacy.ui.LegacyPosterLoader;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class OrionApiClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final Context context;
    private final PreferencesStore preferencesStore;
    private final CookieStore cookieStore;
    private final OkHttpClient client;
    private final Gson gson = new Gson();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LegacyPosterLoader posterLoader;

    public OrionApiClient(Context context, PreferencesStore preferencesStore) {
        this.context = context.getApplicationContext();
        this.preferencesStore = preferencesStore;
        this.cookieStore = new CookieStore(preferencesStore);
        this.client = LegacyHttpCompat.newBuilder().build();
        this.posterLoader = new LegacyPosterLoader(this.context, preferencesStore);
    }

    public String getBaseUrl() {
        return preferencesStore.getSettings().apiBaseUrl;
    }

    public String normalizeBaseUrl(String input) {
        if (input == null) return "";
        String value = input.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.length() == 0) return "";
        if (value.startsWith("http://") || value.startsWith("https://")) {
            try {
                URI uri = new URI(value);
                if (uri.getScheme() != null && uri.getHost() != null) {
                    StringBuilder normalized = new StringBuilder();
                    normalized.append(uri.getScheme()).append("://").append(uri.getAuthority());
                    return normalized.toString();
                }
            } catch (URISyntaxException ignored) {
                return value;
            }
            return value;
        }
        String host = value;
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        if (host.matches("([0-9]{1,3}\\.){3}[0-9]{1,3}(:[0-9]+)?") || host.indexOf(':') >= 0) {
            return "http://" + host;
        }
        return "https://" + host;
    }

    public String imageProxyUrl(String imageUrl) {
        if (imageUrl == null) return "";
        String value = imageUrl.trim();
        if (value.length() == 0) return "";

        // Historical play records may already contain a proxied poster URL.
        // Unwrap first so the recent-play page never nests image-proxy calls.
        value = unwrapImageProxyUrl(value);

        String base = getBaseUrl();
        if (base == null || base.length() == 0) {
            return value;
        }
        return base + "/api/image-proxy?url=" + enc(value);
    }

    private String unwrapImageProxyUrl(String value) {
        String current = value;
        final String marker = "/api/image-proxy?url=";

        for (int depth = 0; depth < 3; depth++) {
            int index = current.indexOf(marker);
            if (index < 0) break;

            String encoded = current.substring(index + marker.length());
            int amp = encoded.indexOf('&');
            if (amp >= 0) encoded = encoded.substring(0, amp);

            try {
                String decoded = URLDecoder.decode(encoded, "UTF-8");
                if (decoded.length() == 0 || decoded.equals(current)) break;
                current = decoded;
            } catch (Exception ignored) {
                break;
            }
        }
        return current;
    }

    public void loadImage(String url, ImageView target, int placeholderResId) {
        posterLoader.load(url, target, placeholderResId, 0, 0);
    }

    public void loadImage(String url,
                          ImageView target,
                          int placeholderResId,
                          int requestedWidthPx,
                          int requestedHeightPx) {
        posterLoader.load(url, target, placeholderResId,
                requestedWidthPx, requestedHeightPx);
    }

    public void cancelImage(ImageView target) {
        posterLoader.cancel(target);
    }

    public void shutdown() {
        posterLoader.shutdown();
    }

    public void login(String username, String password, ApiCallback<LoginResult> callback) {
        JsonObject body = new JsonObject();
        if (username != null) body.addProperty("username", username);
        if (password != null) body.addProperty("password", password);
        post("/api/login", body, LoginResult.class, callback);
    }

    public void logout(final ApiCallback<LoginResult> callback) {
        request("POST", "/api/logout", null, LoginResult.class, new ApiCallback<LoginResult>() {
            @Override
            public void onSuccess(LoginResult value) {
                cookieStore.clear();
                callback.onSuccess(value);
            }

            @Override
            public void onError(Throwable error) {
                callback.onError(error);
            }
        });
    }

    public void getServerConfig(final ApiCallback<ServerConfig> callback) {
        get("/api/server-config", ServerConfig.class, new ApiCallback<ServerConfig>() {
            @Override
            public void onSuccess(ServerConfig value) {
                preferencesStore.saveServerConfig(value);
                callback.onSuccess(value);
            }

            @Override
            public void onError(Throwable error) {
                callback.onError(error);
            }
        });
    }

    public void getDoubanData(String type, String tag, int pageSize, int pageStart, ApiCallback<DoubanResponse> callback) {
        String url = "/api/douban?type=" + enc(type) + "&tag=" + enc(tag)
                + "&pageSize=" + pageSize + "&pageStart=" + pageStart;
        get(url, DoubanResponse.class, callback);
    }

    public void searchVideos(String query, ApiCallback<SearchResponse> callback) {
        get("/api/search?q=" + enc(query), SearchResponse.class, callback);
    }

    public void searchVideo(String query, String resourceId, ApiCallback<SearchResponse> callback) {
        get("/api/search/one?q=" + enc(query) + "&resourceId=" + enc(resourceId), SearchResponse.class, callback);
    }

    public void getResources(ApiCallback<List<ApiSite>> callback) {
        Type type = new TypeToken<List<ApiSite>>() {}.getType();
        get("/api/search/resources", type, callback);
    }

    public void getVideoDetail(String source, String id, ApiCallback<VideoDetail> callback) {
        get("/api/detail?source=" + enc(source) + "&id=" + enc(id), VideoDetail.class, callback);
    }

    public void getFavorites(ApiCallback<Map<String, Favorite>> callback) {
        Type type = new TypeToken<Map<String, Favorite>>() {}.getType();
        get("/api/favorites", type, callback);
    }

    public void addFavorite(String key, Favorite favorite, ApiCallback<MutationResult> callback) {
        JsonObject body = new JsonObject();
        body.addProperty("key", key);
        body.add("favorite", gson.toJsonTree(favorite));
        post("/api/favorites", body, MutationResult.class, callback);
    }

    public void deleteFavorite(String key, ApiCallback<MutationResult> callback) {
        String url = key == null ? "/api/favorites" : "/api/favorites?key=" + enc(key);
        request("DELETE", url, null, MutationResult.class, callback);
    }

    public void getPlayRecords(ApiCallback<Map<String, PlayRecord>> callback) {
        Type type = new TypeToken<Map<String, PlayRecord>>() {}.getType();
        get("/api/playrecords", type, callback);
    }

    public void savePlayRecord(String key, PlayRecord record, ApiCallback<MutationResult> callback) {
        JsonObject body = new JsonObject();
        body.addProperty("key", key);
        body.add("record", gson.toJsonTree(record));
        post("/api/playrecords", body, MutationResult.class, callback);
    }

    public void deletePlayRecord(String key, ApiCallback<MutationResult> callback) {
        String url = key == null ? "/api/playrecords" : "/api/playrecords?key=" + enc(key);
        request("DELETE", url, null, MutationResult.class, callback);
    }

    public void getSearchHistory(ApiCallback<List<String>> callback) {
        Type type = new TypeToken<List<String>>() {}.getType();
        get("/api/searchhistory", type, callback);
    }

    public void addSearchHistory(String keyword, ApiCallback<List<String>> callback) {
        JsonObject body = new JsonObject();
        body.addProperty("keyword", keyword);
        Type type = new TypeToken<List<String>>() {}.getType();
        request("POST", "/api/searchhistory", body, type, callback);
    }

    public void deleteSearchHistory(String keyword, ApiCallback<MutationResult> callback) {
        String url = keyword == null ? "/api/searchhistory" : "/api/searchhistory?keyword=" + enc(keyword);
        request("DELETE", url, null, MutationResult.class, callback);
    }

    public void get(String path, Class<?> clazz, ApiCallback callback) {
        request("GET", path, null, clazz, callback);
    }

    public void get(String path, Type type, ApiCallback callback) {
        request("GET", path, null, type, callback);
    }

    private void post(String path, JsonObject body, Class<?> clazz, ApiCallback callback) {
        request("POST", path, body, clazz, callback);
    }

    private void request(String method, String path, JsonObject body, final Object targetType, final ApiCallback callback) {
        String baseUrl = getBaseUrl();
        if (baseUrl == null || baseUrl.length() == 0) {
            deliverError(callback, new ApiException("API_URL_NOT_SET"));
            return;
        }

        Request.Builder builder = new Request.Builder().url(baseUrl + path);
        String cookies = cookieStore.getCookieHeader();
        if (cookies != null && cookies.length() > 0) {
            builder.header("Cookie", cookies);
        }

        if ("POST".equals(method)) {
            builder.post(RequestBody.create(JSON, body == null ? "{}" : gson.toJson(body)));
        } else if ("DELETE".equals(method)) {
            builder.delete();
        } else {
            builder.get();
        }

        client.newCall(builder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                deliverError(callback, adaptError(e, "网络请求"));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                List<String> setCookies = response.headers("Set-Cookie");
                if (setCookies != null) {
                    for (int i = 0; i < setCookies.size(); i++) {
                        cookieStore.saveFromHeader(setCookies.get(i));
                    }
                }

                String text = response.body() == null ? "" : response.body().string();
                if (response.code() == 401) {
                    deliverError(callback, new ApiException(401, "UNAUTHORIZED"));
                    return;
                }
                if (!response.isSuccessful()) {
                    deliverError(callback, new ApiException(response.code(), "HTTP " + response.code()));
                    return;
                }

                try {
                    Object parsed;
                    if (targetType instanceof Class) {
                        parsed = gson.fromJson(text, (Class) targetType);
                    } else {
                        parsed = gson.fromJson(text, (Type) targetType);
                    }
                    deliverSuccess(callback, parsed);
                } catch (RuntimeException e) {
                    deliverError(callback, adaptError(e, "响应解析"));
                }
            }
        });
    }

    private Throwable adaptError(Throwable error, String action) {
        if (LegacyHttpCompat.isTlsProblem(error)) {
            return new ApiException(LegacyHttpCompat.buildCompatMessage(action));
        }
        return error;
    }

    private String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private void deliverSuccess(final ApiCallback callback, final Object value) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(value);
            }
        });
    }

    private void deliverError(final ApiCallback callback, final Throwable error) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(error);
            }
        });
    }
}
