package DothanProxy;

public class DothanConfigItem {
    public String serverHost;
    public int serverPort;
    public int listenPort;

    public DothanConfigItem(String serverHost, int serverPort, int listenPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.listenPort = listenPort;
    }
}
