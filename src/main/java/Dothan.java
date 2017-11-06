import DothanProxy.DothanVerticle;
import io.vertx.core.Vertx;

public class Dothan {
    public static void main(String[] args) {
        if(args.length<2){
            System.out.println("ARGS LACK");
            return;
        }
        String host=args[0];
        String port=args[1];
        System.out.println("parameters: host is "+host+" and port is "+Integer.parseInt(port));
        Vertx.vertx().deployVerticle(new DothanVerticle(host,Integer.parseInt(port)));
    }
}
