Dothan 是一个轻量的TCP转发工具。

## 起源

采购了大量的云数据库，于是在日常运维中遇到了一个常见的问题，就是突发性的批量变更针对运维和开发用的IP白名单。
为了这个去对接阿里云的API好像有点浪费，于是想出来一个折中的办法，将所有RDS对一个地址开放，然后在其上设置TCP转发。
这样的话，变更只需要在转发上做一次就可以了。

于是捣鼓了最早的试验版（v1），然后一点点加功能，一直到现在第7个大版本，已经出现在了Maven中央仓库。

顺便，这个玩意可以解决某某塔的此地无银三百两式的数据安（垄）全（断）策略。

Dothan 本身是一个地名，中文译名为多坍。

> 那人说：“他们已经走了，我听见他们说要往多坍去。”约瑟就去追赶他哥哥们，遇见他们在多坍。 （创世记 37:17）

> 王说：“你们去探他在哪里，我好打发人去捉拿他。”有人告诉王说：“他在多坍”。 …… 以利沙对他们说：“这不是那道，也不是那城，你们跟我去，我必领你们到所寻找的人那里。”于是领他们到了撒马利亚。 （列王记下 6:13,19）

## 主要功能

最新版本包含的主要功能如下

* Socks级别的TCP端口转发，可用于代理MySQL连接
* 可以针对客户端设置黑名单和白名单
* 支持配置热重载，监控配置文件变更并自动启用新配置
* 支持加密传输，在代理连接两端分别加解密，保证互联网上数据加密传输

## 获取可执行文件

你可以从 [Release](https://github.com/sinri/Dothan/releases) 页面找到最新的可执行文件。

如果你愿意自己编译，可以直接通过Maven进行打包。

如果你希望将Dothan集成进你自己的项目，可以使用Maven，在POM中添加如下依赖即可：

```xml
<dependency>
  <groupId>io.github.sinri</groupId>
  <artifactId>Dothan</artifactId>
  <version>7.0.0</version>
</dependency>
```

## 使用方法

可执行文件为JAR包，需要通过JAVA 17环境运行。其基本的参数列表如下。

```
-b <arg>   blacklist, separate IP with comma (as of 4.0)
-c <arg>   Set proxy config file. If not use this, h,p and l are needed.
-d         deprecated alias for -v
-h <arg>   database host
-help      Display help information
-k         keep config and no hot update
-l <arg>   listen local port
-p <arg>   database port
-w <arg>   whitelist, separate IP with comma (as of 4.0)
-v         enable verbose diagnostics
```

### 日志与隐私

请使用 `-v` 启用详细诊断日志。旧选项 `-d` 在整个 7.x 版本线中仍作为 `-v` 的等价别名保留，但从 7.1 起已废弃；使用时会输出迁移警告，并计划在下一个主版本中移除。同时使用两个选项时仍会启用详细诊断日志，并输出 `-d` 的废弃警告。这些日志仅记录地址、配置版本、流量方向和转发字节数等连接元数据。无论使用何种日志级别，Dothan 都不会记录传输密钥、TLS 存储口令或 TCP 载荷内容。

详细模式可能为每个转发缓冲区生成一条元数据日志，因此高吞吐量或数据高度分片的连接会增加日志量。请继续实施常规的日志保留和访问控制；这两个选项不提供载荷检查能力。

如果你只是为了简单尝试下点对点代理功能，可以直接用命令行参数配置，不需要附带配置文件。
需要使用集群代理、数据加密、配置热重载的功能，需要使用配置文件模式。

### 通过命令行快速运行

例如，有一个远端目标数据库（database.com:3306），中转到中转机的20001端口，并且将详情输出的话，可以在中转机运行如下指令。

```bash
java -jar Dothan.jar -v -h database.com -p 3306 -l 20001
```

成功启动代理后，远端的目标数据库的3306端口已经被映射到了中转机的20001端口。

### 通过配置文件运行

需要编辑一个配置文件（例如在 /path/to/Dothan.config ），并在命令行中启用之。

```bash
java -jar Dothan.jar -v -c /path/to/Dothan.config
```

一般而言，一个配置文件以如下部分组成。

#### 配置类型和版本声明

定义此文件为Dothan Config文件并设定此文件的版本号（VERSION_CODE，应该是一个整数，初始版本可以为0）。

```
# Dothan Config Version VERSION_CODE
```

如果Dothan未设定`-k`指令禁用配置热重载，定义的版本号增大后，Dothan将重新按照新版本的配置重新启动转发。

#### 白名单和黑名单（可选）

白名单为以`+ `开头的IP，一行一条。如果配置文件中不存在任何白名单条目，则认为未设置白名单。

黑名单为以`- `开头的IP，一行一条。如果配置文件中不存在任何黑名单条目，则认为未设置黑名单。

例如

```
+ 127.0.0.1
- 192.168.0.2
```

#### 端口映射配置

按照之前的例子，中转机的监听端口为20001，远端的目标机为database.com:3306，那么这一条规则可以定义为

```
20001:database.com:3306
```

在中转机的监听端口不重复的条件下，可以定义许多规则。

#### 安全传输（可选）

本地 `ENCRYPT` 节点和远端 `DECRYPT` 节点之间的请求与响应都会受到保护。面向客户端及真实服务的两段仍为普通 TCP，只有两个 Dothan 节点之间使用安全传输。

````
                  [CLIENT] 
                    |  ↑
       PLAIN TEXT   |  |   PLAIN TEXT
                    ↓  |
      encrypt [DOTHAN   LOCAL] decrypt
                    |  ↑
       认证加密数据 |  | 认证加密数据
                    ↓  |
      decrypt [DOTHAN  REMOTE] encrypt
                    |  ↑
       PLAIN TEXT   |  |   PLAIN TEXT
                    ↓  |
             [SERVICE PROVIDER]
````

##### RECORD 传输（默认）

`RECORD` 保持原有 `MODE` 和 `TRANSFER KEY` 配置格式。它采用带版本及长度字段的记录协议，为两个方向分别通过 PBKDF2-HMAC-SHA256 派生密钥，使用 AES-256-GCM、相互绑定的新鲜对端握手头、独立随机盐和 nonce 前缀，并严格检查递增序列号。

中转机 `DOTHAN REMOTE` 使用 `DECRYPT` 模式：

```
# MODE DECRYPT
# TRANSFER KEY 请替换为足够长的随机密钥

[DOTHAN_REMOTE_PORT]:[SERVER_ADDRESS]:[SERVER_PORT]

```


接收机 `DOTHAN LOCAL` 使用 `ENCRYPT` 模式，并配置相同密钥：

```
# MODE ENCRYPT
# TRANSFER KEY 请替换为足够长的随机密钥

[DOTHAN_LOCAL_PORT]:[DOTHAN_REMOTE_ADDRESS]:[DOTHAN_REMOTE_PORT]

```

可以显式添加 `# SECURE TRANSPORT RECORD`，省略时也默认使用 `RECORD`。请使用高熵随机密钥，不要使用容易猜测的口令。

版本 2 握手在每个方向发送一个 58 字节头：`DTHN` 魔数、版本、发送方角色、16 字节盐、4 字节 nonce 前缀和 32 字节 HMAC。每个方向的流量密钥都会绑定对端新生成且已认证的握手头；交换完成前不会接收应用数据，握手超时为 10 秒。后续每帧包含 32 位长度、64 位序列号，以及最多 16 KiB 密文和 GCM 标签。nonce 由本方向前缀与序列号组成；缺失、重复、乱序、过大或认证失败的记录都会被拒绝。零长度认证记录用于关闭流，因此帧边界处的截断也能被检测。

##### TLS 传输

TLS 模式要求两个节点使用私有 CA 签发的身份。每个 PKCS#12 密钥库包含本节点的私钥和证书链，信任库则信任签发对端证书的 CA。远端证书的 DNS 或 IP SAN 必须匹配 `ENCRYPT` 节点配置的连接地址。

```text
# MODE ENCRYPT
# SECURE TRANSPORT TLS
# TLS KEYSTORE PATH /secure/dothan-local.p12
# TLS KEYSTORE PASSWORD 请替换
# TLS TRUSTSTORE PATH /secure/dothan-ca.p12
# TLS TRUSTSTORE PASSWORD 请替换

[DOTHAN_LOCAL_PORT]:[DOTHAN_REMOTE_ADDRESS]:[DOTHAN_REMOTE_PORT]
```

远端节点使用相同指令，将模式改为 `# MODE DECRYPT` 并使用自己的身份密钥库。Dothan 仅启用 TLS 1.3，校验远端主机名和双方证书，并强制要求客户端证书。TLS 模式不使用 `TRANSFER KEY`。

##### 升级兼容性

新的安全记录格式取代了旧版 AES/ECB 数据流。由于 `RECORD` 是默认值，已有配置文件可以继续使用，但新旧版本的加密节点不能互通，必须同时升级一条加密链路的两端。`PLAIN` 模式不受影响。认证、分帧或解密失败时，Dothan 会关闭代理连接的两端。

理论上，中间可以使用多组 Dothan 完成数据接力。

#### 注释

除了上述定义的指令之外，以`#`开头的行都被当做注释处理。
