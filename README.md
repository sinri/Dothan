# Dothan

A light TCP proxy. You can take it as MySQL proxy, might also work for SSH.

_其实我就是嫌弃阿里云的RDS，白名单还不能像ECS挂安全组。_ 

> And the man said, They are departed hence; for I heard them say, Let us go to Dothan. And Joseph went after his brethren, and found them in Dothan. (Genesis 37:17 )

## Environment

* Java 8

## Maven Dependencies

* io.vertx core
* Apache commons cli

## License

GPLv3

## Get Runnable Package

You can obtain the runnable package in two ways.

1. Download JAR file from GitHub release area.
1. Use Maven 3, run `mvn clean package`. 


## Usage (As of Version 2.0)

usage: options

     -c <arg>   Set proxy config file. If not use this, h,p and l are needed.
     -d         use detail mode
     -h <arg>   database host
     -help      Display help information
     -l <arg>   listen local port
     -p <arg>   database port

### Quick Proxy 

Run Dothan quickly for one proxy, *h*ost, *p*ort and *l*isten port are required, and *d*etail mode is also available.

    java -jar Dothan.jar -d -hdatabase.com -p3306 -l20001

### Configured Proxy

You should provide a config file as *c*onfig parameter.

The config file should contain one or more lines and each for one proxy plan. 
The lines with leading Sharp(#) would be treated as comments. 
Here is an example:

````
# Dothan Config [SAMPLE]

20001 1.rds.aliyuncs.com:3306
20002 2.rds.aliyuncs.com:3306
````

The command would be as following if the config file path is  `/path/to/Dothan.config`.

    java -jar Dothan.jar -d -c /path/to/Dothan.config

### Hot Update Version

As of version 3.0, the hot version update is available for Config-File Mode.

This relies on the version declaration in configuration file as a line:

    # Dothan Config Version VERSION_CODE

The version code should be an integer. 
If there are more than one line in this format, only the first would be used. 
The file would be watched by the Dothan process and update config if the current version code became greater.

----

## Museum: Version 1.1 (deprecated)

    java -jar target/Dothan-1.1-SNAPSHOT.jar some.mysql.rds.aliyuncs.com 3306 33306
