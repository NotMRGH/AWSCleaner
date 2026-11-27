package ir.mrstudios.awscleaner.network;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

public class DirectSocketFactory extends SocketFactory {
    @Override
    public Socket createSocket() {
        return new Socket(Proxy.NO_PROXY);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return connect(new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return connect(new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return connect(new InetSocketAddress(host, port), new InetSocketAddress(localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress localHost, int localPort) throws IOException {
        return connect(new InetSocketAddress(host, port), new InetSocketAddress(localHost, localPort));
    }

    private Socket connect(InetSocketAddress target, InetSocketAddress local) throws IOException {
        Socket socket = createSocket();
        try {
            if (local != null) socket.bind(local);
            socket.connect(target);
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }
}
