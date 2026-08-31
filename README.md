# Dothan

![GitHub Release](https://img.shields.io/github/release/sinri/Dothan.svg)
![GitHub](https://img.shields.io/github/license/sinri/Dothan.svg)
[![Docs Site](https://img.shields.io/badge/link-docs-blue.svg)](https://sinri.github.io/Dothan/)

A light TCP proxy. You can take it as MySQL proxy, might also work for SSH.

> And the man said, They are departed hence; for I heard them say, Let us go to Dothan. And Joseph went after his brethren, and found them in Dothan. (Genesis 37:17 )

> And he said, Go and spy where he [is], that I may send and fetch him. And it was told him, saying, Behold, [he is] in Dothan....And Elisha said unto them, This [is] not the way, neither [is] this the city: follow me, and I will bring you to the man whom ye seek. But he led them to Samaria. (2 Kings 6:13,19)

## Package

Since Version 6, new package `io.github.sinri.Dothan` is adopted to the this project.

## Maven Access

[OSS-SONATYPE](https://oss.sonatype.org/content/groups/public/io/github/sinri/Dothan/)

```xml
<dependency>
  <groupId>io.github.sinri</groupId>
  <artifactId>Dothan</artifactId>
  <version>7.0.0</version>
</dependency>
```

### Deploy Note

You should export your `JAVA_HOME` first. In Mac OS X, you may run `/usr/libexec/java_home` to get correct path for it.

Commonly snapshot is used for quick deploy with default version tag `6.0-SNAPSHOT` or so, run `mvn clean package deploy -P snapshot` to update. 

To release new version to OSS-SONATYPE, run `mvn clean package deploy -P release` after correcting the version.

## Environment

* Java 17

## Maven Dependencies

* io.vertx core
* Apache commons cli
* Apache commons validator

## License

GPLv3

## Get Runnable Package

You can obtain the runnable package in two ways.

1. Download JAR file from GitHub release area.
1. Use Maven 3, run `mvn clean package`. 


## Usage (As of Version 2.0)

usage: options

     -b <arg>   blacklist, separate IP with comma (as of 4.0)
     -c <arg>   Set proxy config file. If not use this, h,p and l are needed.
     -d         use detail mode
     -h <arg>   database host
     -help      Display help information
     -k         keep config and no hot update
     -l <arg>   listen local port
     -p <arg>   database port
     -w <arg>   whitelist, separate IP with comma (as of 4.0)
     -v         verbose

### Quick Proxy 

Run Dothan quickly for one proxy, *h*ost, *p*ort and *l*isten port are required, and *d*etail mode is also available.

    java -jar Dothan.jar -d -hdatabase.com -p3306 -l20001

### Configured Proxy

You should provide a config file as *c*onfig parameter.

The config file should contain one or more lines and each for one proxy requirement.
The format is `[LISTEN_PORT]:[SERVER_HOST]:[SERVER_PORT]` as of 5.0. 
Previous format `[LISTEN_PORT] [SERVER_HOST]:[SERVER_PORT]` is still supported though not recommended now. 

The lines with leading Sharp(#) would be treated as comments. 

The lines with leading Plus(+) would be treated as IP Address in whitelist. If no such lines, whitelist is ignored.

The lines with leading Minus(-) would be treated as IP Address in blacklist. If no such lines, blacklist is ignored.

Here is an example:

````
# Dothan Config [SAMPLE]

+ 127.0.0.1
- 192.168.0.2

20001:1.rds.aliyuncs.com:3306
20002:2.rds.aliyuncs.com:3306
````

The command would be as following if the config file path is  `/path/to/Dothan.config`.

    java -jar Dothan.jar -d -c /path/to/Dothan.config

### Hot Update Version

As of version 3.0, the hot version update is available for Config-File Mode.

This relies on the version declaration in configuration file as a line:

    # Dothan Config Version VERSION_CODE

The version code must be a non-negative integer, and the file must contain at most one version declaration.
The file is watched by the Dothan process, but a candidate is published only when its version is strictly greater than the active version.

Since version 3.1, Dothan uses WatchService for hot update function, and provide a parameter (-k) to disable hot update.

As of version 7.1, hot updates use immutable configuration snapshots. Dothan reads and validates the entire candidate in isolation, including routes, ports, addresses, modes, and required security settings. Invalid, partial, stale, or undeployable candidates are rejected without changing the active snapshot. Removing optional whitelist, blacklist, mode, key, or TLS declarations resets them to their documented defaults instead of retaining values from an older version.

New listening ports are opened before publication. Existing ports stay open, and every accepted connection captures exactly one snapshot, so established connections continue with their original route and security settings while new connections use the newly published version. Removed ports stop accepting new connections and close after their existing connections drain. Listener deployment or shutdown failures are reported through the asynchronous lifecycle rather than mutable polling state.

## Secure Transport

Dothan can protect both directions of the link between a local `ENCRYPT` node and a remote `DECRYPT` node. Application-facing and service-facing links remain plain TCP; only the Dothan-to-Dothan link is protected.

````
                  [CLIENT] 
                    |  ↑
       PLAIN TEXT   |  |   PLAIN TEXT
                    ↓  |
      encrypt [DOTHAN   LOCAL] decrypt
                    |  ↑
      AUTHENTICATED  |  | AUTHENTICATED
       ENCRYPTION    |  | ENCRYPTION
                    ↓  |
      decrypt [DOTHAN  REMOTE] encrypt
                    |  ↑
       PLAIN TEXT   |  |   PLAIN TEXT
                    ↓  |
             [SERVICE PROVIDER]
````

### RECORD transport (default)

`RECORD` keeps the existing `MODE` and `TRANSFER KEY` configuration. It uses a versioned, length-prefixed record protocol with per-direction PBKDF2-HMAC-SHA256 key derivation, AES-256-GCM, fresh mutually bound peer headers, independent random salts and nonce prefixes, and strictly increasing sequence numbers.

On `DOTHAN LOCAL`:

```
# MODE ENCRYPT
# TRANSFER KEY REPLACE_WITH_A_LONG_RANDOM_SECRET

[DOTHAN_LOCAL_PORT]:[DOTHAN_REMOTE_ADDRESS]:[DOTHAN_REMOTE_PORT]

```

On `DOTHAN REMOTE`, use the same secret:

```
# MODE DECRYPT
# TRANSFER KEY REPLACE_WITH_A_LONG_RANDOM_SECRET

[DOTHAN_REMOTE_PORT]:[SERVER_ADDRESS]:[SERVER_PORT]

```

`# SECURE TRANSPORT RECORD` may be specified explicitly, but `RECORD` is the default. Use a high-entropy secret rather than a human password.

The version 2 handshake starts with a 58-byte header in each direction: `DTHN` magic, version, sender role, 16-byte salt, 4-byte nonce prefix, and a 32-byte HMAC. Each traffic key is bound to the other peer's fresh authenticated header. No application bytes are accepted until that exchange completes, and the handshake times out after 10 seconds. Each following frame contains a 32-bit length, a 64-bit sequence number, and up to 16 KiB of ciphertext plus its GCM tag. The nonce is the direction's prefix followed by its sequence number; missing, repeated, reordered, oversized, or unauthenticated records are rejected. A zero-length authenticated record closes the stream, so truncation at a frame boundary is also detected.

### TLS transport

For standard TLS, configure both nodes with identities signed by a private CA. Each PKCS#12 keystore contains that node's private key and certificate chain; each truststore trusts the CA used to sign the other node. The remote certificate must contain a DNS or IP subject alternative name matching the address used by the `ENCRYPT` node.

```text
# MODE ENCRYPT
# SECURE TRANSPORT TLS
# TLS KEYSTORE PATH /secure/dothan-local.p12
# TLS KEYSTORE PASSWORD REPLACE_ME
# TLS TRUSTSTORE PATH /secure/dothan-ca.p12
# TLS TRUSTSTORE PASSWORD REPLACE_ME

[DOTHAN_LOCAL_PORT]:[DOTHAN_REMOTE_ADDRESS]:[DOTHAN_REMOTE_PORT]
```

The remote node uses the same directives with `# MODE DECRYPT` and its own identity keystore. Dothan permits TLS 1.3 only, verifies the remote hostname, validates certificates in both directions, and requires a client certificate. `TRANSFER KEY` is not used by TLS transport.

### Upgrade compatibility

The secure record wire format replaces the legacy AES/ECB stream. Existing configuration remains valid because `RECORD` is the default, but old and new encrypted nodes cannot communicate. Upgrade both ends of every encrypted link together. `PLAIN` mode is unchanged. Authentication, framing, or decryption errors close both sides of the proxied connection.

If you like, you can use more than one Dothan pair to make the connection chain.

----

## Museum: Version 1.1 (deprecated)

    java -jar target/Dothan-1.1-SNAPSHOT.jar some.mysql.rds.aliyuncs.com 3306 33306
