
package edu.ucla.library.ezid.crawler;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.freelibrary.util.FileExtFileFilter;
import info.freelibrary.util.FileUtils;
import info.freelibrary.util.StringUtils;

import au.com.bytecode.opencsv.CSVWriter;

public class Crawler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Crawler.class);

    private final File mySourceDir;

    private final String[] myFileExts;

    private final File myOutputFile;

    public Crawler(final File aSourceDir, final File aOutputFile, final String[] aFileExts) {
        myOutputFile = aOutputFile;
        mySourceDir = aSourceDir;
        myFileExts = aFileExts;
    }

    public static void main(final String[] args) throws IOException {
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
            new Crawler(dir, out, exts).crawl();
        }
    }

    public void crawl() throws IOException {
        final File[] files = FileUtils.listFiles(mySourceDir, new FileExtFileFilter(myFileExts), true);
        final CSVWriter csvWriter = new CSVWriter(new FileWriter(myOutputFile));
        final Set<String> set = new HashSet<String>();

        for (final File file : files) {
            final String fileName = file.getName();

            if (set.contains(fileName)) {
                LOGGER.warn("Adding a duplicate simple file name");
            } else if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Adding '{}' to the list of files", file);
            }

            csvWriter.writeNext(new String[] { FileUtils.stripExt(fileName), file.getAbsolutePath() });
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
