package io.github.sinri.Dothan.DothanProxy;

import io.github.sinri.Dothan.Config.DothanConfig;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.PfxOptions;

import java.util.Set;
import java.util.concurrent.TimeUnit;

final class TlsTransportOptions {
    private static final Set<String> TLS_PROTOCOLS = Set.of("TLSv1.3");

    private TlsTransportOptions() {
    }

    static NetClientOptions client(DothanConfig config) {
        return new NetClientOptions()
                .setSsl(true)
                .setKeyCertOptions(identity(config))
                .setTrustOptions(trust(config))
                .setTrustAll(false)
                .setHostnameVerificationAlgorithm("HTTPS")
                .setEnabledSecureTransportProtocols(TLS_PROTOCOLS)
                .setSslHandshakeTimeout(10)
                .setSslHandshakeTimeoutUnit(TimeUnit.SECONDS);
    }

    static NetServerOptions server(DothanConfig config) {
        return new NetServerOptions()
                .setSsl(true)
                .setKeyCertOptions(identity(config))
                .setTrustOptions(trust(config))
                .setClientAuth(ClientAuth.REQUIRED)
                .setEnabledSecureTransportProtocols(TLS_PROTOCOLS)
                .setSslHandshakeTimeout(10)
                .setSslHandshakeTimeoutUnit(TimeUnit.SECONDS);
    }

    private static PfxOptions identity(DothanConfig config) {
        return new PfxOptions()
                .setPath(config.getTlsKeyStorePath())
                .setPassword(config.getTlsKeyStorePassword());
    }

    private static PfxOptions trust(DothanConfig config) {
        return new PfxOptions()
                .setPath(config.getTlsTrustStorePath())
                .setPassword(config.getTlsTrustStorePassword());
    }
}
