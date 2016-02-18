package edu.ucla.library.ezid.crawler;

import static org.junit.Assert.fail;

import java.util.Properties;

import org.junit.Before;
import org.junit.Test;

public class CrawlerTest {

    private String myUsername;

    private String myPassword;

    private String myShoulder;

    @Before
    public void beforeTest() {
        final Properties properties = System.getProperties();

        if (!properties.containsKey(Crawler.EZID_USER)) {
            fail("EZID test user not set");
        } else {
            myUsername = properties.getProperty(Crawler.EZID_USER);
        }

        if (!properties.containsKey(Crawler.EZID_PSWD)) {
            fail("EZID test user password not set");
        } else {
            myPassword = properties.getProperty(Crawler.EZID_PSWD);
        }

        if (!properties.containsKey(Crawler.ARK_SHOULDER)) {
            fail("ARK shoulder not set");
        } else {
            myShoulder = properties.getProperty(Crawler.ARK_SHOULDER);
        }
    }

    @Test
    public void testCrawl() {

    }

}
