package com.oriontv.legacy.net;

import android.os.Build;

import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.TlsVersion;

public final class LegacyHttpCompat {
    private LegacyHttpCompat() {
    }

    public static OkHttpClient.Builder newBuilder() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true);
        enableLegacyTls(builder);
        return builder;
    }

    public static OkHttpClient newClient() {
        return newBuilder().build();
    }

    private static void enableLegacyTls(OkHttpClient.Builder builder) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return;
        }
        try {
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore) null);
            TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
            if (trustManagers == null || trustManagers.length == 0 || !(trustManagers[0] instanceof X509TrustManager)) {
                return;
            }
            X509TrustManager trustManager = (X509TrustManager) trustManagers[0];
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());
            builder.sslSocketFactory(new Tls12SocketFactory(sslContext.getSocketFactory()), trustManager);
            ConnectionSpec compatibleTls = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
                    .allEnabledCipherSuites()
                    .build();
            List<ConnectionSpec> specs = Arrays.asList(compatibleTls, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT);
            builder.connectionSpecs(specs);
        } catch (Exception ignored) {
            builder.connectionSpecs(Collections.singletonList(ConnectionSpec.CLEARTEXT));
        }
    }

    public static boolean isTlsProblem(Throwable error) {
        if (error == null) {
            return false;
        }
        Throwable current = error;
        while (current != null) {
            String name = current.getClass().getName();
            String message = current.getMessage();
            if (name != null) {
                name = name.toLowerCase();
                if (name.contains("ssl") || name.contains("tls") || name.contains("cert")) {
                    return true;
                }
            }
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("ssl") || lower.contains("tls") || lower.contains("handshake") || lower.contains("certificate")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    public static String buildCompatMessage(String action) {
        if (action == null || action.length() == 0) {
            action = "请求";
        }
        return action + "失败：当前 Android 4.0.4 与目标 HTTPS/TLS 不兼容，请在设置中改用兼容服务地址或切换线路。";
    }
}
