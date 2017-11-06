import DothanProxy.DothanVerticle;
import io.vertx.core.Vertx;

public class Dothan {
    public static void main(String[] args) {
        System.out.println("Dothan 1.1");
        if(args.length!=3){
            System.out.println("Usage: java -jar Dothan.jar SERVER_HOST SERVER_PORT LISTEN_PORT");
            return;
        }
        String host=args[0];
        String port=args[1];
        String listenPort=args[2];
        System.out.println("Listen on port "+Integer.parseInt(listenPort)+", proxy for server "+host+":"+Integer.parseInt(port));
        Vertx.vertx().deployVerticle(new DothanVerticle(host,Integer.parseInt(port),Integer.parseInt(listenPort)));
    }
}
