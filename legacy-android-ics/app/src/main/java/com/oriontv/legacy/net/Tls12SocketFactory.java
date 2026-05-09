package com.oriontv.legacy.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class Tls12SocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory delegate;

    Tls12SocketFactory(SSLSocketFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return enable(delegate.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return enable(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return enable(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return enable(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return enable(delegate.createSocket(address, port, localAddress, localPort));
    }

    private Socket enable(Socket socket) {
        if (socket instanceof SSLSocket) {
            SSLSocket sslSocket = (SSLSocket) socket;
            String[] protocols = supportedProtocols(sslSocket);
            if (protocols.length > 0) {
                sslSocket.setEnabledProtocols(protocols);
            }
        }
        return socket;
    }

    private String[] supportedProtocols(SSLSocket socket) {
        String[] supported = socket.getSupportedProtocols();
        List<String> enabled = new ArrayList<String>();
        addIfSupported(enabled, supported, "TLSv1.2");
        addIfSupported(enabled, supported, "TLSv1.1");
        addIfSupported(enabled, supported, "TLSv1");
        return enabled.toArray(new String[enabled.size()]);
    }

    private void addIfSupported(List<String> enabled, String[] supported, String protocol) {
        if (supported == null || protocol == null) {
            return;
        }
        for (int i = 0; i < supported.length; i++) {
            if (protocol.equals(supported[i])) {
                enabled.add(protocol);
                return;
            }
        }
    }
}
