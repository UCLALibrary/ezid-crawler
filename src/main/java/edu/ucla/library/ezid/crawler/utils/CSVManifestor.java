
package edu.ucla.library.ezid.crawler.utils;

import java.awt.Dimension;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import info.freelibrary.jiiify.iiif.presentation.json.AbstractIiifResourceMixIn;
import info.freelibrary.jiiify.iiif.presentation.json.ManifestMixIn;
import info.freelibrary.jiiify.iiif.presentation.json.MetadataLocalizedValueMixIn;
import info.freelibrary.jiiify.iiif.presentation.json.ServiceMixIn;
import info.freelibrary.jiiify.iiif.presentation.model.AbstractIiifResource;
import info.freelibrary.jiiify.iiif.presentation.model.Canvas;
import info.freelibrary.jiiify.iiif.presentation.model.Manifest;
import info.freelibrary.jiiify.iiif.presentation.model.Sequence;
import info.freelibrary.jiiify.iiif.presentation.model.other.Image;
import info.freelibrary.jiiify.iiif.presentation.model.other.ImageResource;
import info.freelibrary.jiiify.iiif.presentation.model.other.MetadataLocalizedValue;
import info.freelibrary.jiiify.iiif.presentation.model.other.Resource;
import info.freelibrary.jiiify.iiif.presentation.model.other.Service;
import info.freelibrary.jiiify.util.PathUtils;
import info.freelibrary.util.FileUtils;
import info.freelibrary.util.StringUtils;

import au.com.bytecode.opencsv.CSVReader;
import io.vertx.core.json.JsonObject;

public class CSVManifestor {

    static {
        final TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {

            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            @Override
            public void checkClientTrusted(final java.security.cert.X509Certificate[] certs, final String authType) {
            }

            @Override
            public void checkServerTrusted(final java.security.cert.X509Certificate[] certs, final String authType) {
            }
        } };

        try {
            final SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (final GeneralSecurityException details) {
            throw new RuntimeException(details);
        }

        final HostnameVerifier allHostsValid = new HostnameVerifier() {

            @Override
            public boolean verify(final String hostname, final SSLSession session) {
                return true;
            }
        };

        HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CSVManifestor.class);

    /* File of images to include in the manuscript file */
    private static final File CSV = new File("/home/kevin/sinai-filtered.csv");

    private static final File MANIFEST = new File("/home/kevin/sinai-manifest.json");

    /* ARK for the Manuscript itself -- this particular one is for Arabic_NF_8 */
    private static final String MANUSCRIPT_ARK = "ark:/21198/z1kd1z25";

    private static final String SERVER = "https://stage-images.library.ucla.edu";

    private static final String SERVICE_PREFIX = "/iiif/";

    private static final String IIIF_SERVER = SERVER + SERVICE_PREFIX;

    private static final String IIIF_PROFILE = "http://iiif.io/api/image/2/level0.json";

    private static final String IIIF_CONTEXT = "http://iiif.io/api/image/2/context.json";

    public CSVManifestor() {
    }

    public static void main(final String[] args) throws IOException, URISyntaxException {
        final SinaiComparator comparator = new CSVManifestor().new SinaiComparator();
        final CSVReader csvReader = new CSVReader(new FileReader(CSV));
        final List<String[]> sources = csvReader.readAll();
        final String thumbnailID = sources.get(0)[0]; // for now, just using first image
        final Manifest manifest = getManifest(MANUSCRIPT_ARK, thumbnailID);
        final List<Sequence> sequences = manifest.getSequences();

        String canvasName = "";
        int canvasCount = 0;
        int startIndex = 0;

        // Get our sources in the order we want (so color images have preference)
        Collections.sort(sources, comparator);

        if (sequences.add(getSequence(MANUSCRIPT_ARK, 0))) {
            final List<Canvas> canvases = new ArrayList<Canvas>();

            for (int index = 0; index < sources.size(); index++) {
                final String name = getCanvasName(sources.get(index)[1]);

                if (!name.equals(canvasName)) {
                    if (!canvasName.equals("")) {
                        final Canvas canvas = getCanvas(MANUSCRIPT_ARK, canvasName, ++canvasCount);
                        final List<Image> images = new ArrayList<Image>();

                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Processing canvas: {}", canvas.getId());
                        }

                        for (int count = 1; startIndex <= index; startIndex++) {
                            final String[] source = sources.get(startIndex);
                            final String canvasId = canvas.getId();

                            images.add(getImage(MANUSCRIPT_ARK, source[0], source[1], canvasId, count++));
                        }

                        final Image image = images.get(0);
                        final ImageResource resource = (ImageResource) image.getResource();
                        final Service service = resource.getService();

                        canvas.setImages(images);
                        canvas.setWidth(resource.getWidth());
                        canvas.setHeight(resource.getHeight());
                        canvas.setThumbnail(getThumbnail(getBareID(service.getId())));
                        canvases.add(canvas);
                    }

                    canvasName = name;
                    startIndex = index;
                }
            }

            sequences.get(sequences.size() - 1).setCanvases(canvases);
        }

        org.codehaus.plexus.util.FileUtils.fileWrite(MANIFEST, "UTF-8", toJson(manifest));
        csvReader.close();
    }

    private static final Image getImage(final String aObjID, final String aImageID, final String aLabel,
            final String aOn, final int aCount) throws URISyntaxException, MalformedURLException, IOException {
        final String imageId = getID(IIIF_SERVER, PathUtils.encodeIdentifier(aObjID), "imageanno", aCount);
        final String label = FileUtils.stripExt(new File(aLabel));

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Processing image: {}", imageId);
        }

        final Image image = new Image(imageId);
        final ImageResource resource = new ImageResource();
        final Service service = new Service(IIIF_SERVER + PathUtils.encodeIdentifier(aImageID) + "/info.json");
        final Dimension dims = getHeightWidth(aImageID);

        image.setOn(aOn);
        service.setProfile(IIIF_PROFILE);
        service.setContext(IIIF_CONTEXT);
        service.setLabel(label);
        resource.setHeight(dims.height);
        resource.setWidth(dims.width);
        resource.setFormat("image/jpeg");
        resource.setService(service);
        image.setResource(resource);

        return image;
    }

    private static final Canvas getCanvas(final String aID, final String aLabel, final int aCount)
            throws URISyntaxException, MalformedURLException, IOException {
        final String id = getID(IIIF_SERVER, PathUtils.encodeIdentifier(aID), "canvas", aCount);
        final Canvas canvas = new Canvas(id, aLabel, 0, 0);

        return canvas;
    }

    private static final String getBareID(final String aURI) {
        return aURI.substring(aURI.indexOf(SERVICE_PREFIX) + SERVICE_PREFIX.length()).replace("/info.json", "");
    }

    private static final Dimension getHeightWidth(final String aImageID) throws URISyntaxException,
            MalformedURLException, IOException {
        final URL url = new URL(IIIF_SERVER + PathUtils.encodeIdentifier(aImageID) + "/info.json");
        final HttpsURLConnection https = (HttpsURLConnection) url.openConnection();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(https.getInputStream()));
        final StringBuilder sb = new StringBuilder();
        final Dimension dimension = new Dimension();
        final JsonObject json;

        String line;

        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }

        reader.close();
        https.disconnect();

        json = new JsonObject(sb.toString());
        dimension.setSize(json.getInteger("width"), json.getInteger("height"));

        return dimension;
    }

    private static final Sequence getSequence(final String aID, final int aCount) throws URISyntaxException {
        final Sequence sequence = new Sequence();

        sequence.setId(getID(IIIF_SERVER, PathUtils.encodeIdentifier(aID), "sequence", aCount));
        sequence.setLabel(aID);

        return sequence;
    }

    private static final Manifest getManifest(final String aID, final String aThumbnail) throws URISyntaxException,
            IOException {
        final Manifest manifest = new Manifest(IIIF_SERVER + PathUtils.encodeIdentifier(aID) + "/manifest", aID);

        manifest.setLogo(SERVER + "/images/logos/iiif_logo.png");
        manifest.setThumbnail(getThumbnail(aThumbnail));
        manifest.setSequences(new ArrayList<Sequence>());

        return manifest;
    }

    private static final String getThumbnail(final String aID) throws URISyntaxException, MalformedURLException,
            IOException {
        final String solrTemplate = SERVER.replace("https", "http") + ":8983/solr/jiiify/select?q=\"{}\"&wt=json";
        final URL url = new URL(StringUtils.format(solrTemplate, PathUtils.encodeIdentifier(aID)));
        final HttpURLConnection http = (HttpURLConnection) url.openConnection();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(http.getInputStream()));
        final StringBuilder sb = new StringBuilder();
        final String thumbnail;
        final JsonObject json;

        String line;

        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }

        reader.close();
        http.disconnect();

        json = new JsonObject(sb.toString());

        // We're not doing any exception catching here... we expect it to be there or else we fail
        thumbnail = json.getJsonObject("response").getJsonArray("docs").getJsonObject(0).getString(
                "jiiify_thumbnail_s");

        return IIIF_SERVER + thumbnail.replace("/iiif/", "");
    }

    private static final String getCanvasName(final String aImagePath) {
        final String[] parts = aImagePath.split("\\/");
        return parts[parts.length - 2];
    }

    private static final String getID(final String aHost, final String aID, final String aType, final int aCount) {
        final StringBuilder builder = new StringBuilder(aHost).append(aID).append('/').append(aType);
        return builder.append('/').append(aType).append("-").append(aCount).toString();
    }

    private static final String toJson(final Manifest manifest) throws JsonProcessingException {
        final ObjectMapper mapper = new ObjectMapper();

        mapper.addMixIn(AbstractIiifResource.class, AbstractIiifResourceMixIn.class);
        mapper.addMixIn(Image.class, AbstractIiifResourceMixIn.class);
        mapper.addMixIn(Manifest.class, ManifestMixIn.class);
        mapper.addMixIn(MetadataLocalizedValue.class, MetadataLocalizedValueMixIn.class);
        mapper.addMixIn(Resource.class, AbstractIiifResourceMixIn.class);
        mapper.addMixIn(Service.class, ServiceMixIn.class);
        mapper.setSerializationInclusion(Include.NON_NULL);

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
    }

    /**
     * Comparator that sorts by file name except when one of the file names ends with _color.tiff, then that's given
     * preference.
     *
     * @author Kevin S. Clarke <a href="mailto:ksclarke@ksclarke.io">ksclarke@ksclarke.io</a>
     */
    class SinaiComparator implements Comparator<String[]> {

        @Override
        public int compare(final String[] a1stSource, final String[] a2ndSource) {
            final String[] splits1 = a1stSource[1].split("/");
            final String[] splits2 = a2ndSource[1].split("/");
            final String dirName1 = splits1[splits1.length - 2];
            final String dirName2 = splits2[splits2.length - 2];
            final String fileName1 = splits1[splits1.length - 1];
            final String fileName2 = splits2[splits2.length - 1];
            final String colorTIFF = "_color.tif";

            if (dirName1.equals(dirName2)) {
                if (fileName1.endsWith(colorTIFF)) {
                    return -1;
                } else if (fileName2.endsWith(colorTIFF)) {
                    return 1;
                } else {
                    return fileName1.compareToIgnoreCase(fileName2);
                }
            } else {
                return a1stSource[1].compareToIgnoreCase(a2ndSource[1]);
            }
        }
    }
}