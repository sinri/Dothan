package process;

import io.github.sinri.Dothan.Dothan;

public class ProcessTest {
    public static void main(String[] args) {
        // test for the case port used
        String[] x = {"-d", "-h", "127.0.0.1", "-p", "80", "-l", "80"};
        Dothan.main(x);
    }
}
