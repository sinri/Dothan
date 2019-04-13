package io.github.sinri.Dothan.DothanProxy;

public class DothanProxyRequirement {
    public String serverHost;
    public int serverPort;
    public int listenPort;

    public DothanProxyRequirement(String serverHost, int serverPort, int listenPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.listenPort = listenPort;
    }
}
