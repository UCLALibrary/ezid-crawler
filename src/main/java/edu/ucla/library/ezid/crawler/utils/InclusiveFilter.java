
package edu.ucla.library.ezid.crawler.utils;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;

public class InclusiveFilter {

    private static final File SOURCE = new File("/home/kevin/syriac-arks.csv");

    private static final File INCLUDES = new File("/home/kevin/syriac-includes.csv");

    private static final Logger LOGGER = LoggerFactory.getLogger(InclusiveFilter.class);

    public InclusiveFilter() {
    }

    public static void main(final String[] args) throws IOException {
        final List<String> patterns = new ArrayList<String>();
        final CSVReader iReader = new CSVReader(new FileReader(INCLUDES));
        final CSVReader sReader = new CSVReader(new FileReader(SOURCE));
        final CSVWriter writer = new CSVWriter(new FileWriter(new File("/home/kevin/syriac-filtered.csv")));

        String[] sourceData;
        String[] includes;

        while ((includes = iReader.readNext()) != null) {
            final String include = ".*\\/" + includes[0].replace("*", ".*");

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Adding an include pattern to the list of includes: {}", include);
            }

            patterns.add(include);
        }

        while ((sourceData = sReader.readNext()) != null) {
            for (int index = 0; index < patterns.size(); index++) {
                if (sourceData[1].matches(patterns.get(index))) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Adding file to the list to be ingested: {}", sourceData[0], sourceData[1]);
                    }

                    writer.writeNext(sourceData);
                }
            }
        }

        iReader.close();
        sReader.close();
        writer.close();
    }

}
