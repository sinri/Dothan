package DothanProxy;

public class DothanHelper {

    public static boolean isDetailMode() {
        return detailMode;
    }

    public static void setDetailMode(boolean detailMode) {
        DothanHelper.detailMode = detailMode;
    }

    private static boolean detailMode = false;

}
