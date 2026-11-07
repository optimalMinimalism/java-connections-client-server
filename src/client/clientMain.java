package client;

import client.net.tcpClient;
import client.net.udpListener;
import client.state.clientState;
import client.ui.commandLoop;
import common.util.configLoader;

public class clientMain {

    public static void main(String[] args) throws Exception {
        configLoader config = new configLoader("config/client-config.properties");

        String host = config.getString("server.host");
        int tcp_port = config.getInt("server.tcp.port");
        int udp_port = config.getInt("client.udp.port");

        clientState client_state = new clientState();
        client_state.setUdpPort(udp_port);

        tcpClient tcp_client = new tcpClient(host, tcp_port);
        tcp_client.connect();
        client_state.setConnected(true);

        udpListener udp_listener = new udpListener(udp_port,
                Math.multiplyExact(config.getLong("client.udp.fragment.timeout.seconds"), 1000L),
                config.getInt("client.udp.receive.timeout.millis"));
        Thread udp_thread = new Thread(udp_listener, "connections-udp-listener");
        udp_thread.setDaemon(true);
        udp_thread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            udp_listener.stop();
            tcp_client.close();
        }));

        commandLoop loop = new commandLoop(tcp_client, client_state);
        try {
            loop.run();
        } finally {
            udp_listener.stop();
            tcp_client.close();
        }
    }
}
