package DothanProxy;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

public class DothanHelper {

    public static boolean isDetailMode() {
        return detailMode;
    }

    public static void setDetailMode(boolean detailMode) {
        DothanHelper.detailMode = detailMode;
    }

    private static boolean detailMode = false;

    public static final Logger logger= LoggerFactory.getLogger(DothanHelper.class);


}
