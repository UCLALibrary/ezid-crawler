
package edu.ucla.library.ezid.crawler.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
import info.freelibrary.util.StringUtils;

import au.com.bytecode.opencsv.CSVWriter;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class Submanifestor {

    // private static final String SOURCE = "/home/kevin/syriac-manifest.json";

    // private static final String MANIFEST_PATH = "/home/kevin/syriac-manifests/{}-manifest.json";

    // private static final String MANIFESTS_CSV = "/home/kevin/syriac-manifests/syriac.csv";

    private static final String SOURCE = "/home/kevin/sinai-manifest.json";

    private static final String MANIFEST_PATH = "/home/kevin/sinai-manifests/{}-manifest.json";

    private static final String MANIFESTS_CSV = "/home/kevin/sinai-manifests/sinai.csv";

    private static final String SERVER = "https://stage-images.library.ucla.edu";

    private static final String SERVICE_PREFIX = "/iiif/";

    private static final String IIIF_SERVER = SERVER + SERVICE_PREFIX;

    private static final String IIIF_PROFILE = "http://iiif.io/api/image/2/level0.json";

    private static final String IIIF_CONTEXT = "http://iiif.io/api/image/2/context.json";

    public static void main(final String[] args) throws IOException, URISyntaxException {
        final CSVWriter csvWriter = new CSVWriter(new FileWriter(MANIFESTS_CSV));
        final JsonObject source = new JsonObject(StringUtils.read(new File(SOURCE)));
        final JsonArray jsonCanvases = source.getJsonArray("sequences").getJsonObject(0).getJsonArray("canvases");
        final String objID = source.getString("label"); // object ARK
        final List<String[]> csvData = new ArrayList<String[]>();

        for (int index = 0; index < jsonCanvases.size(); index++) {
            final JsonObject jsonCanvas = jsonCanvases.getJsonObject(index);
            final String label = jsonCanvas.getString("label");
            final JsonArray images = jsonCanvas.getJsonArray("images");
            final String thumbnail = jsonCanvas.getString("thumbnail");
            final String id = objID + "/" + label;
            final Manifest manifest = getManifest(id, label, thumbnail);
            final Sequence sequence = getSequence(id, label, 1);
            final List<Canvas> canvases = new ArrayList<Canvas>();

            csvData.add(new String[] { id, label });

            for (int imgIndex = 0; imgIndex < images.size(); imgIndex++) {
                final JsonObject jsonImage = images.getJsonObject(imgIndex);
                final JsonObject jsonResource = jsonImage.getJsonObject("resource");
                final int width = jsonResource.getInteger("width").intValue();
                final int height = jsonResource.getInteger("height").intValue();
                final JsonObject jsonService = jsonResource.getJsonObject("service");
                final String serviceLabel = jsonService.getString("label");
                // Fix for bad master manifests -- shouldn't contain info.json in service ID
                final String serviceId = jsonService.getString("@id").replace("/info.json", "");
                final Canvas canvas = getCanvas(id, serviceLabel, imgIndex + 1, width, height);
                final String imageId = getID(IIIF_SERVER, PathUtils.encodeIdentifier(id), "imageanno", 1);
                final Image image = new Image(imageId);
                final ImageResource resource = new ImageResource();
                final Service service = new Service(serviceId);

                image.setOn(canvas.getId());
                service.setProfile(IIIF_PROFILE);
                service.setContext(IIIF_CONTEXT);
                service.setLabel(canvas.getLabel());
                resource.setHeight(height);
                resource.setWidth(width);
                resource.setFormat("image/jpeg");
                resource.setService(service);
                image.setResource(resource);
                canvas.setImages(Arrays.asList(new Image[] { image }));

                if (!serviceLabel.endsWith("_color")) {
                    canvas.setThumbnail(getThumbnail(getBareID(serviceId)));
                    canvases.add(canvas);
                }
            }

            sequence.setCanvases(canvases);
            manifest.setSequences(Arrays.asList(new Sequence[] { sequence }));

            final String path = StringUtils.format(MANIFEST_PATH, label);
            final FileWriter writer = new FileWriter(new File(path));

            writer.write(toJson(manifest));
            writer.close();
        }

        csvWriter.writeAll(csvData);
        csvWriter.close();
    }

    private static final String getBareID(final String aURI) {
        return aURI.substring(aURI.indexOf(SERVICE_PREFIX) + SERVICE_PREFIX.length()).replace("/info.json", "");
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

    private static final Canvas getCanvas(final String aID, final String aLabel, final int aCount, final int aWidth,
            final int aHeight) throws URISyntaxException, MalformedURLException, IOException {
        final String id = getID(IIIF_SERVER, PathUtils.encodeIdentifier(aID), "canvas", aCount);
        final Canvas canvas = new Canvas(id, aLabel, aWidth, aHeight);

        return canvas;
    }

    private static final Sequence getSequence(final String aID, final String aLabel, final int aCount)
            throws URISyntaxException {
        final Sequence sequence = new Sequence();

        sequence.setId(getID(IIIF_SERVER, PathUtils.encodeIdentifier(aID), "sequence", aCount));
        sequence.setLabel(aLabel);

        return sequence;
    }

    private static final Manifest getManifest(final String aID, final String aLabel, final String aThumbnail)
            throws URISyntaxException, IOException {
        final Manifest manifest = new Manifest(IIIF_SERVER + PathUtils.encodeIdentifier(aID) + "/manifest", aLabel);

        manifest.setLogo(SERVER + "/images/logos/iiif_logo.png");
        manifest.setThumbnail(aThumbnail);
        manifest.setSequences(new ArrayList<Sequence>());

        return manifest;
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
}
