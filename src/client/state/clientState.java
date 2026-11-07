package client.state;

public class clientState {

    private boolean connected;
    private boolean logged_in;
    private String username;
    private int udp_port;

    public clientState() {
        this.connected = false;
        this.logged_in = false;
        this.username = null;
        this.udp_port = 0;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean isLoggedIn() {
        return logged_in;
    }

    public void setLoggedIn(boolean logged_in) {
        this.logged_in = logged_in;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getUdpPort() {
        return udp_port;
    }

    public void setUdpPort(int udp_port) {
        this.udp_port = udp_port;
    }

    public void clearLogin() {
        this.logged_in = false;
        this.username = null;
    }
}
