package com.oriontv.legacy.media;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class M3u8Inspector {
    private static final Pattern RESOLUTION = Pattern.compile("RESOLUTION=\\d+x(\\d+)", Pattern.CASE_INSENSITIVE);
    private final OkHttpClient client = new OkHttpClient();

    public String inspectResolution(String url) {
        if (url == null || !url.toLowerCase().contains(".m3u8")) {
            return null;
        }
        Response response = null;
        try {
            response = client.newCall(new Request.Builder().url(url).build()).execute();
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            String body = response.body().string();
            Matcher matcher = RESOLUTION.matcher(body);
            int max = 0;
            while (matcher.find()) {
                int height = Integer.parseInt(matcher.group(1));
                if (height > max) max = height;
            }
            return max > 0 ? max + "p" : null;
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (response != null) response.close();
        }
    }
}
