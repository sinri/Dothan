package io.github.sinri.Dothan.DothanProxy;

public class DothanProxyRequirement {
    public final String serverHost;
    public final int serverPort;
    public final int listenPort;

    public DothanProxyRequirement(String serverHost, int serverPort, int listenPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.listenPort = listenPort;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DothanProxyRequirement that)) {
            return false;
        }
        return serverPort == that.serverPort
                && listenPort == that.listenPort
                && serverHost.equals(that.serverHost);
    }

    @Override
    public int hashCode() {
        int result = serverHost.hashCode();
        result = 31 * result + serverPort;
        result = 31 * result + listenPort;
        return result;
    }
}
