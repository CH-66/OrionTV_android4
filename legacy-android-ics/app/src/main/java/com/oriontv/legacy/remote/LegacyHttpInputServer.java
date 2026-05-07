package com.oriontv.legacy.remote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Enumeration;

public class LegacyHttpInputServer {
    public interface Listener {
        void onMessage(String message);
        void onHandshake();
    }

    private static final int PORT = 12346;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private ServerSocket serverSocket;
    private Thread thread;
    private volatile boolean running;

    public LegacyHttpInputServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public String start() throws IOException {
        if (running) {
            return "http://" + getIpAddress() + ":" + PORT;
        }
        serverSocket = new ServerSocket(PORT);
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "orion-remote-input");
        thread.start();
        return "http://" + getIpAddress() + ":" + PORT;
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        serverSocket = null;
    }

    private void loop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                handle(socket);
            } catch (IOException ignored) {
            }
        }
    }

    private void handle(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                socket.close();
                return;
            }

            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && line.length() > 0) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }

            char[] bodyChars = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = reader.read(bodyChars, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            String body = new String(bodyChars, 0, read);

            if (requestLine.startsWith("GET / ")) {
                write(socket, 200, "text/html; charset=utf-8", page());
            } else if (requestLine.startsWith("POST /handshake ")) {
                notifyHandshake();
                write(socket, 200, "application/json", "{\"status\":\"ok\"}");
            } else if (requestLine.startsWith("POST /message ")) {
                notifyMessage(extractMessage(body));
                write(socket, 200, "application/json", "{\"status\":\"ok\"}");
            } else {
                write(socket, 404, "text/plain", "Not Found");
            }
        } catch (Exception e) {
            try {
                write(socket, 500, "text/plain", "Internal Server Error");
            } catch (IOException ignored) {
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void write(Socket socket, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        String statusText = status == 200 ? "OK" : status == 404 ? "Not Found" : "Internal Server Error";
        String headers = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(headers.getBytes("UTF-8"));
        output.write(bytes);
        output.flush();
    }

    private void notifyMessage(final String message) {
        if (listener == null || message == null || message.length() == 0) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onMessage(message);
            }
        });
    }

    private void notifyHandshake() {
        if (listener == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onHandshake();
            }
        });
    }

    private String extractMessage(String body) {
        if (body == null) return "";
        int jsonIndex = body.indexOf("\"message\"");
        if (jsonIndex >= 0) {
            int colon = body.indexOf(':', jsonIndex);
            int start = body.indexOf('"', colon + 1);
            int end = body.indexOf('"', start + 1);
            if (start >= 0 && end > start) {
                return body.substring(start + 1, end);
            }
        }
        String key = "message=";
        int form = body.indexOf(key);
        if (form >= 0) {
            try {
                return URLDecoder.decode(body.substring(form + key.length()), "UTF-8");
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private String page() {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>OrionTV Remote</title><style>body{font-family:sans-serif;background:#121212;color:#fff;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}"
                + "div{width:90%;max-width:420px}input,button{width:100%;box-sizing:border-box;font-size:18px;padding:14px;margin:8px 0;border-radius:6px;border:0}button{background:#00bb5e;color:white;font-weight:bold}</style></head>"
                + "<body><div><h3>发送到电视</h3><input id=\"text\" autofocus placeholder=\"请输入搜索内容\"><button onclick=\"send()\">发送</button></div>"
                + "<script>fetch('/handshake',{method:'POST'});function send(){var v=document.getElementById('text').value;if(!v)return;fetch('/message',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message:v})});document.getElementById('text').value='';}</script>"
                + "</body></html>";
    }

    private String getIpAddress() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
            if (info != null && info.getType() == ConnectivityManager.TYPE_WIFI) {
                WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                WifiInfo wifiInfo = wifiManager == null ? null : wifiManager.getConnectionInfo();
                if (wifiInfo != null) {
                    int ip = wifiInfo.getIpAddress();
                    return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
                }
            }
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.getHostAddress().indexOf(':') < 0) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }
}
