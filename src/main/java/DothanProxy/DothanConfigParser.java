package DothanProxy;

import io.vertx.core.logging.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;

public class DothanConfigParser {
    private String configFilePath;

    public DothanConfigParser(String filePath) {
        this.configFilePath = filePath;
    }

    public ArrayList<DothanConfigItem> getConfigItems() throws IOException {
        ArrayList<DothanConfigItem> configItems = new ArrayList<>();
        Files.lines((new File(this.configFilePath)).toPath()).forEach(s -> {
            s = s.trim();
            if (s.startsWith("#") || s.isEmpty()) {
                return;
            }
            if (!s.matches("^\\d+\\s+[^:\\s]+:\\d+$")) {
                LoggerFactory.getLogger(this.getClass()).error("Cannot parse this line, ignore it: \n" + s);
                return;
            }
            String[] s1 = s.split("\\s+");
            String vl = s1[0];
            String[] s2 = s1[1].split(":");
            String vh = s2[0];
            String vp = s2[1];

            DothanConfigItem dci = new DothanConfigItem(vh, Integer.parseInt(vp), Integer.parseInt(vl));
            configItems.add(dci);
        });
        return configItems;
    }

    public int getVersion() {
        int version = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(this.configFilePath))) {

            String sCurrentLine;

            while ((sCurrentLine = br.readLine()) != null) {
                if (sCurrentLine.matches("^# Dothan Config Version \\d+$")) {
                    String str_version = sCurrentLine.trim().substring(24);
                    version = Integer.parseInt(str_version);
                    break;
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        return version;

    }

}
