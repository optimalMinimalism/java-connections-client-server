package server.session;

import com.google.gson.annotations.SerializedName;

public class playerSession {

    @SerializedName("accountId")
    private String account_id;
    private boolean logged_in;
    private String client_ip;
    private int udp_port;
    private boolean connected;

    public playerSession() {
        this.logged_in = false;
        this.connected = true;
    }

    public playerSession(String client_ip) {
        this();
        this.client_ip = client_ip;
    }

    public String getAccountId() {
        return account_id;
    }

    public void setAccountId(String account_id) {
        this.account_id = account_id;
    }

    public boolean isLoggedIn() {
        return logged_in;
    }

    public void setLoggedIn(boolean logged_in) {
        this.logged_in = logged_in;
    }

    public String getClientIp() {
        return client_ip;
    }

    public void setClientIp(String client_ip) {
        this.client_ip = client_ip;
    }

    public int getUdpPort() {
        return udp_port;
    }

    public void setUdpPort(int udp_port) {
        this.udp_port = udp_port;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public void clearLogin() {
        this.account_id = null;
        this.logged_in = false;
        this.udp_port = 0;
    }

    @Override
    public String toString() {
        return "PlayerSession{" +
                "account_id='" + account_id + '\'' +
                ", loggedIn=" + logged_in +
                ", clientIp='" + client_ip + '\'' +
                ", udpPort=" + udp_port +
                ", connected=" + connected +
                '}';
    }
}
