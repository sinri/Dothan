package io.github.sinri.Dothan.DothanProxy;

public enum DothanTransferModeEnum {
    PLAIN("PLAIN"),
    ENCRYPT("ENCRYPT"),
    DECRYPT("DECRYPT");

    private String name;

    // 定义一个带参数的构造器，枚举类的构造器只能使用 private 修饰
    DothanTransferModeEnum(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
