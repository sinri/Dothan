package DothanProxy;

public class DothanHelper {

    public static String transferKey = null;
    private static boolean detailMode = false;
    private static DothanTransferModeEnum transferMode = DothanTransferModeEnum.PLAIN;

    public static boolean isDetailMode() {
        return detailMode;
    }

    public static void setDetailMode(boolean detailMode) {
        DothanHelper.detailMode = detailMode;
    }

    public static DothanTransferModeEnum getTransferMode() {
        return transferMode;
    }

    public static void setTransferMode(DothanTransferModeEnum transferMode) {
        DothanHelper.transferMode = transferMode;
    }

    public static String getTransferKey() {
        return transferKey;
    }

    public static void setTransferKey(String transferKey) {
        DothanHelper.transferKey = transferKey;
    }

    public enum DothanTransferModeEnum {
        PLAIN("PLAIN"),
        ENCRYPT("ENCRYPT"),
        DECRYPT("DECRYPT");

        private String name;

        // 定义一个带参数的构造器，枚举类的构造器只能使用 private 修饰
        private DothanTransferModeEnum(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
