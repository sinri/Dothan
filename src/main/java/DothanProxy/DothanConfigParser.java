package DothanProxy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.function.Consumer;

public class DothanConfigParser {
    private String configFilePath;

    public DothanConfigParser(String filePath) {
        this.configFilePath = filePath;
    }

    public ArrayList<DothanConfigItem> getConfigItems() throws IOException {
        ArrayList<DothanConfigItem> configItems = new ArrayList<>();
        Files.lines((new File(this.configFilePath)).toPath()).forEach(new Consumer<String>() {
            @Override
            public void accept(String s) {
                s = s.trim();
                if (s.startsWith("#") || s.isEmpty()) {
                    return;
                }
                if (!s.matches("^\\d+\\s+[^:\\s]+:\\d+$")) {
                    DothanHelper.logger.error("Cannot parse this line, ignore it: \n" + s);
                    return;
                }
                String[] s1 = s.split("\\s+");
                String vl = s1[0];
                String[] s2 = s1[1].split(":");
                String vh = s2[0];
                String vp = s2[1];

                DothanConfigItem dci = new DothanConfigItem(vh, Integer.parseInt(vp), Integer.parseInt(vl));
                configItems.add(dci);
            }
        });
        return configItems;
    }
}
