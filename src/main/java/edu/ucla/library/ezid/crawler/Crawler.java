
package edu.ucla.library.ezid.crawler;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.freelibrary.util.FileExtFileFilter;
import info.freelibrary.util.FileUtils;
import info.freelibrary.util.Stopwatch;
import info.freelibrary.util.StringUtils;

import au.com.bytecode.opencsv.CSVWriter;
import edu.ucsb.nceas.ezid.EZIDException;
import edu.ucsb.nceas.ezid.EZIDService;

public class Crawler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Crawler.class);

    public static final String EZID_USER = "ezid.username";

    public static final String EZID_PSWD = "ezid.password";

    public static final String ARK_SHOULDER = "ark.shoulder";

    private final File mySourceDir;

    private final String[] myFileExts;

    private final File myOutputFile;

    public Crawler(final File aSourceDir, final File aOutputFile, final String[] aFileExts) {
        myOutputFile = aOutputFile;
        mySourceDir = aSourceDir;
        myFileExts = aFileExts;
    }

    public static void main(final String[] args) throws IOException {
        final Properties properties = System.getProperties();
        final Stopwatch timer = new Stopwatch();

        File dir = null;
        File out = null;
        String[] exts = null;

        if (args.length == 0) {
            LOGGER.warn("Crawler started without any arguments");
            printUsageAndExit();
        }

        for (int index = 0; index < args.length; index++) {
            if (args[index].equals("-d")) {
                dir = new File(args[++index]);

                if (!dir.exists()) {
                    LOGGER.error("Source directory doesn't exist: {}", dir);
                    System.exit(1);
                } else if (dir.exists() && !dir.canRead()) {
                    LOGGER.error("Source directory exists but can't be read: {}", dir);
                    System.exit(1);
                }
            } else if (args[index].equals("-o")) {
                out = new File(args[++index]);

                if (out.exists() && !out.canWrite()) {
                    LOGGER.error("Output CSV file exists and can't be overwritten: {}", out);
                    System.exit(1);
                } else {
                    final File parent = out.getParentFile();

                    if (!parent.exists() && !parent.mkdirs()) {
                        LOGGER.error("CSV file's parent dir doesn't exist and can't be created: {}", parent);
                        System.exit(1);
                    }
                }
            } else if (args[index].equals("-e")) {
                exts = args[++index].replace('.', ',').split("\\,+");

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("File extension patterns ({}): {}", exts.length, StringUtils.toString(exts, ' '));
                }
            }
        }

        if (dir == null || out == null || exts == null || exts.length == 0) {
            LOGGER.warn("Crawler started without all the required arguments");
            printUsageAndExit();
        } else {
            final String username;
            final String password;
            final String shoulder;

            if (!properties.containsKey(EZID_USER) || !properties.containsKey(EZID_PSWD) || !properties.containsKey(
                    ARK_SHOULDER)) {
                final Scanner reader = new Scanner(System.in);
                System.out.println("EZID username: ");
                System.out.print(" ");
                username = reader.nextLine();
                System.out.println("EZID password: ");
                System.out.print(" ");
                password = reader.nextLine();
                System.out.println("ARK shoulder: ");
                System.out.print(" ");
                shoulder = reader.nextLine();
                reader.close();
            } else {
                username = properties.getProperty(EZID_USER);
                password = properties.getProperty(EZID_PSWD);
                shoulder = properties.getProperty(ARK_SHOULDER);
            }

            timer.start();
            new Crawler(dir, out, exts).crawl(username, password, shoulder);
            timer.stop();
            System.out.println("Runtime: " + timer.getMilliseconds());
        }
    }

    public void crawl(final String aUsername, final String aPassword, final String aShoulder) throws IOException {
        final File[] files = FileUtils.listFiles(mySourceDir, new FileExtFileFilter(myFileExts), true);
        final CSVWriter csvWriter = new CSVWriter(new FileWriter(myOutputFile));
        final Set<String> set = new HashSet<String>();
        final EZIDService ezid = new EZIDService();

        try {
            ezid.login(aUsername, aPassword);
        } catch (final EZIDException details) {
            System.err.println("Unable to login to EZID service");
            System.exit(1);
        }

        for (final File file : files) {
            final HashMap<String, String> metadata = new HashMap<String, String>();
            final String fileName = file.getName();

            String id;

            metadata.put("erc.who", "UCLA Digital Library");
            metadata.put("erc.what", FileUtils.stripExt(fileName));
            metadata.put("_status", "reserved");

            if (set.contains(fileName)) {
                LOGGER.warn("Adding a duplicate simple file name");
            } else if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Adding '{}' to the list of files", file);
            }

            try {
                id = ezid.mintIdentifier(aShoulder, metadata);

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Successfully created EZID ARK: {}", id);
                }
            } catch (final EZIDException details) {
                LOGGER.error("Failed to create ARK: {}", details.getMessage());
                id = FileUtils.stripExt(fileName);
            }

            csvWriter.writeNext(new String[] { id, file.getAbsolutePath() });
        }

        csvWriter.close();
    }

    private static final void printUsageAndExit() {
        System.err.println();
        System.err.println("Usage: -d [path to source dir] -o [path to output CSV] -p [file extension patterns]");
        System.err.println(
                "  For example: java -jar ezid-crawler.jar -d /path/to/dir -o /path/to/output.csv -e tiff,png");
        System.err.println();
        System.exit(1);
    }
}
