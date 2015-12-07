
package edu.ucla.library.ezid.crawler.utils;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.ArrayList;

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
import info.freelibrary.jiiify.iiif.presentation.model.other.MetadataLocalizedValue;
import info.freelibrary.jiiify.iiif.presentation.model.other.Resource;
import info.freelibrary.jiiify.iiif.presentation.model.other.Service;
import info.freelibrary.jiiify.util.PathUtils;
import info.freelibrary.util.StringUtils;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class Submanifestor {

    private static final String SOURCE = "/home/kevin/syriac-manifest.json";

    private static final String SERVER = "https://stage-images.library.ucla.edu";

    private static final String SERVICE_PREFIX = "/iiif/";

    private static final String IIIF_SERVER = SERVER + SERVICE_PREFIX;

    public static void main(final String[] args) throws IOException, URISyntaxException {
        final JsonObject source = new JsonObject(StringUtils.read(new File(SOURCE)));
        final JsonArray canvases = source.getJsonArray("sequences").getJsonObject(0).getJsonArray("canvases");
        final String objID = source.getString("label"); // object ARK

        for (int index = 0; index < canvases.size(); index++) {
            final JsonObject jsonCanvas = canvases.getJsonObject(index);
            final String label = jsonCanvas.getString("label");
            final JsonArray images = jsonCanvas.getJsonArray("images");
            final String thumbnail = jsonCanvas.getString("thumbnail");
            final String id = objID + "/" + label;

            final Manifest manifest = getManifest(id, thumbnail);

            for (final int imgIndex = 0; index < images.size(); index++) {
                final JsonObject image = images.getJsonObject(imgIndex);
                final int width = image.getInteger("width");
                final int height = image.getInteger("height");
                final Canvas canvas = getCanvas(id, label, imgIndex, width, height);
            }

            System.out.println(toJson(manifest));
            break;
        }

    }

    private static final Canvas getCanvas(final String aID, final String aLabel, final int aCount, final int aWidth,
            final int aHeight) throws URISyntaxException, MalformedURLException, IOException {
        final String id = getID(IIIF_SERVER, PathUtils.encodeIdentifier(aID), "canvas", aCount);
        final Canvas canvas = new Canvas(id, aLabel, aWidth, aHeight);

        return canvas;
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
