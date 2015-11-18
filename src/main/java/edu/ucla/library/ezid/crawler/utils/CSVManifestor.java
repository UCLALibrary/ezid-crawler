
package edu.ucla.library.ezid.crawler.utils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.plexus.util.FileUtils;
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

import au.com.bytecode.opencsv.CSVReader;

public class CSVManifestor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CSVManifestor.class);

    /* File of images to include in the manuscript file */
    private static final File CSV = new File("/home/kevin/sinai-filtered.csv");

    private static final File MANIFEST = new File("/home/kevin/sinai-manifest.json");

    /* ARK for the Manuscript itself -- this particular one is for Arabic_NF_8 */
    private static final String MANUSCRIPT_ARK = "ark:/21198/z1kd1z25";

    private static final String SERVER = "https://localhost:8443";

    private static final String IIIF_SERVER = SERVER + "/iiif/";

    private static final String IIIF_PROFILE = "http://iiif.io/api/image/2/level0.json";

    private static final String IIIF_CONTEXT = "http://iiif.io/api/image/2/context.json";

    public static void main(final String[] args) throws IOException, URISyntaxException {
        final CSVReader csvReader = new CSVReader(new FileReader(CSV));
        final List<String[]> sources = csvReader.readAll();
        final String thumbnailID = sources.get(0)[0]; // for now, just using first image
        final Manifest manifest = getManifest(MANUSCRIPT_ARK, thumbnailID);
        final List<Sequence> sequences = manifest.getSequences();

        String canvasName = "";
        int canvasCount = 0;
        int startIndex = 0;

        if (sequences.add(getSequence(MANUSCRIPT_ARK, 0))) {
            final List<Canvas> canvases = new ArrayList<Canvas>();

            for (int index = 0; index < sources.size(); index++) {
                final String name = getCanvasName(sources.get(index)[1]);

                if (!name.equals(canvasName)) {
                    if (!canvasName.equals("")) {
                        final Canvas canvas = getCanvas(MANUSCRIPT_ARK, canvasName, ++canvasCount);
                        final List<Image> images = canvas.getImages();

                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Processing canvas: {}", canvas.getId());
                        }

                        for (int count = 1; startIndex <= index; startIndex++) {
                            final String[] source = sources.get(startIndex);

                            images.add(getImage(MANUSCRIPT_ARK, source[0], source[1], canvas.getId(), count++));
                        }

                        canvas.setImages(images);
                        canvases.add(canvas);
                    }

                    canvasName = name;
                    startIndex = index;
                }
            }

            sequences.get(sequences.size() - 1).setCanvases(canvases);
        }

        FileUtils.fileWrite(MANIFEST, "UTF-8", toJson(manifest));
        csvReader.close();
    }

    private static final Image getImage(final String aObjID, final String aImageID, final String aLabel,
            final String aOn, final int aCount) throws URISyntaxException {
        final Image image = new Image(getID(IIIF_SERVER, PathUtils.encodeIdentifier(aObjID), "imageanno", aCount));
        final ImageResource resource = new ImageResource();
        final Service service = new Service(IIIF_SERVER + PathUtils.encodeIdentifier(aImageID));

        image.setOn(aOn);
        service.setProfile(IIIF_PROFILE);
        service.setContext(IIIF_CONTEXT);
        resource.setHeight(getHeight(aImageID));
        resource.setWidth(getWidth(aImageID));
        resource.setFormat("image/jpeg");
        resource.setService(service);
        image.setResource(resource);

        return image;
    }

    private static final Canvas getCanvas(final String aID, final String aLabel, final int aCount)
            throws URISyntaxException {
        final String id = getID(IIIF_SERVER, PathUtils.encodeIdentifier(aID), "canvas", aCount);
        final Canvas canvas = new Canvas(id, aLabel, getHeight(aID), getWidth(aID));

        canvas.setThumbnail(getThumbnail(aID));
        canvas.setImages(new ArrayList<Image>());

        return canvas;
    }

    private static final Sequence getSequence(final String aID, final int aCount) throws URISyntaxException {
        final Sequence sequence = new Sequence();

        sequence.setId(getID(IIIF_SERVER, PathUtils.encodeIdentifier(aID), "sequence", aCount));
        sequence.setLabel(aID);

        return sequence;
    }

    private static final Manifest getManifest(final String aID, final String aThumbnail) throws URISyntaxException {
        final Manifest manifest = new Manifest(IIIF_SERVER + PathUtils.encodeIdentifier(aID) + "/manifest", aID);

        manifest.setLogo(SERVER + "/images/logos/iiif_logo.png");
        manifest.setThumbnail(getThumbnail(aThumbnail));
        manifest.setSequences(new ArrayList<Sequence>());

        return manifest;
    }

    private static final String getThumbnail(final String aID) throws URISyntaxException {
        // We query Solr for this, but we need to have ingested the images first
        return IIIF_SERVER + "thumbnail_for_" + PathUtils.encodeIdentifier(aID) + "_from_solr";
    }

    private static final int getHeight(final String aID) {
        return 500;
    }

    private static final int getWidth(final String aID) {
        return 500;
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
}
