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
-d         use detail mode
-h <arg>   database host
-help      Display help information
-k         keep config and no hot update
-l <arg>   listen local port
-p <arg>   database port
-w <arg>   whitelist, separate IP with comma (as of 4.0)
-v         verbose
```

如果你只是为了简单尝试下点对点代理功能，可以直接用命令行参数配置，不需要附带配置文件。
需要使用集群代理、数据加密、配置热重载的功能，需要使用配置文件模式。

### 通过命令行快速运行

例如，有一个远端目标数据库（database.com:3306），中转到中转机的20001端口，并且将详情输出的话，可以在中转机运行如下指令。

```bash
java -jar Dothan.jar -d -h database.com -p 3306 -l 20001
```

成功启动代理后，远端的目标数据库的3306端口已经被映射到了中转机的20001端口。

### 通过配置文件运行

需要编辑一个配置文件（例如在 /path/to/Dothan.config ），并在命令行中启用之。

```bash
java -jar Dothan.jar -d -c /path/to/Dothan.config
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

#### 加密传输（可选）

加密传输的模型：

````
                  [CLIENT] 
                    |  ↑
       PLAIN TEXT   |  |   PLAIN TEXT
                    ↓  |
      encrypt [DOTHAN   LOCAL] decrypt
                    |  ↑
       SECRET BYTES |  | SECRET BYTES
                    ↓  |
      decrypt [DOTHAN  REMOTE] encrypt
                    |  ↑
       PLAIN TEXT   |  |   PLAIN TEXT
                    ↓  |
             [SERVICE PROVIDER]
````

加密传输需要在中转机(靠近数据库的一端)和接收机（靠近客户端的一端）上都启用Dothan，分别利用`ENCRYPT`和`DECRYPT`两种不同的`MODE`，并配置好相同的`TRANSFER KEY`。

中转机 `DOTHAN REMOTE` 上的Dothan使用`DECRYPT`模式。

```
# MODE DECRYPT
# TRANSFER KEY XXX

[DOTHAN_REMOTE_PORT]:[SERVER_ADDRESS]:[SERVER_PORT]

```


接收机 `DOTHAN LOCAL` 上的Dothan使用`ENCRYPT`模式。

```
# MODE ENCRYPT
# TRANSFER KEY XXX

[DOTHAN_LOCAL_PORT]:[DOTHAN_REMOTE_ADDRESS]:[DOTHAN_REMOTE_PORT]

```

理论上，中间可以中转无数次完成数据接力；不过应该没有这么可怕的应用场景的吧。

#### 注释

除了上述定义的指令之外，以`#`开头的行都被当做注释处理。
