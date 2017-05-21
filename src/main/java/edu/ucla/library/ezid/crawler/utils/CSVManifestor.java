
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
import java.util.Arrays;    
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
import info.freelibrary.jiiify.iiif.presentation.model.other.Metadata;
import info.freelibrary.jiiify.iiif.presentation.model.other.MetadataLocalizedValue;
import info.freelibrary.jiiify.iiif.presentation.model.other.Resource;
import info.freelibrary.jiiify.iiif.presentation.model.other.Service;
import info.freelibrary.jiiify.util.PathUtils;
import info.freelibrary.util.FileUtils;
import info.freelibrary.util.StringUtils;

import au.com.bytecode.opencsv.CSVReader;
import io.vertx.core.json.JsonObject;

import com.fasterxml.jackson.annotation.JsonProperty;

/*
 * An image resource for images with alternate resources, per IIIF Presentation 2.0
 */
class ChoiceImageResource extends Resource {
	private Resource myDefault;
	private final List<Resource> myItem;

	public ChoiceImageResource() {
		setType("oa:Choice");
		myItem = new ArrayList<Resource>();
	}

	public Resource getDefault() {
		return myDefault;
	}

	public void setDefault(final Resource aDefault) {
		myDefault = aDefault;
	}

	public List<Resource> getItem() {
		return myItem;
	}

	public void addAltItem(final Resource aItem) {
		myItem.add(aItem);
	}
}

/*
 * The ImageResource class does not have label or thumbnail fields, so this adds those.
 */
class ImageResourceWithLabelAndThumbnail extends ImageResource {
	private String myLabel;
    private String myThumbnail;
    
	public String getLabel() {
		return myLabel;
	}
	public void setLabel(final String aLabel) {
		myLabel = aLabel;
	}
    
    public String getThumbnail() {
        return myThumbnail;
    }

    public void setThumbnail(final String aThumbnail) {
        myThumbnail = aThumbnail;
    }
}

/*
 * Used in place of info.freelibrary.jiiify.iiif.presentation.model.other.MetadataSimple,
 * which doesn't have a getValue method. This problem caused the toJson function to omit 
 * the myValue field.
 */
class MetadataSimpleWithGetter extends Metadata {
    private final String myValue;

    /**
     * Creates a simple metadata element.
     *
     * @param aLabel A metadata label
     * @param aValue A metadata value
     */
    public MetadataSimpleWithGetter(final String aLabel, final String aValue) {
        super(aLabel);
        myValue = aValue;
    }

    public String getValue() {
        return myValue;
    }
}

public class CSVManifestor {

	static {
		final TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {

			public java.security.cert.X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}

			public void checkClientTrusted(final java.security.cert.X509Certificate[] certs, final String authType) {
			}

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

			public boolean verify(final String hostname, final SSLSession session) {
				return true;
			}
		};

		HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(CSVManifestor.class);

	private static final String TEST_SERVER = "https://test-sinai-images.library.ucla.edu";

	private static final String STAGE_SERVER = "https://stage-sinai-images.library.ucla.edu";

	private static final String PROD_SERVER = "https://sinai-images.library.ucla.edu";

	private static final String SERVICE_PREFIX = "/iiif/";

	private static final String IIIF_PROFILE = "http://iiif.io/api/image/2/level0.json";

	private static final String IIIF_CONTEXT = "http://iiif.io/api/image/2/context.json";

	private final File myImageCSVFile;

	private final File myMetadataCSVFile;

	private final File myThumbnailCSVFile;

	private final File myManifestFile;

	private final String myManifestARK;

    private final String myManifestLabel;
    
	private final String myServer;
 
	private final String myIiifServer;

    private final int myWidth;

    private final int myHeight;

    /*
     * Constructor.
     *
     * @param {File} aImageCSVFile      CSV file that maps manuscript ARKs to the filepaths of image resources to include in the manifest
     * @param {File} aMetadataCSVFile   CSV file that maps manuscript ARKs to manuscript metadata
     * @param {File} aThumbnailCSVFile  CSV file that maps image ARKs to thumbnail URLs
     * @param {File} aManifestFile      output file to write to
     * @param {String} aARKIdentifier   ARK to use for this manuscript
     * @param {String} aServer          label of the server on which this manifest file will be hosted
     * @param {String} aDimensions      width,height of the images (assuming they are all the same dimensions) - e.g. "788,999"
     *
     * aThumbnailCSVFile and aDimensions are optional, but they must either both be present or both be absent
     */
	public CSVManifestor(final File aImageCSVFile, final File aMetadataCSVFile, final File aThumbnailCSVFile, final File aManifestFile, final String aARKIdentifier, final String aServer, final String aDimensions) throws IOException {
		myImageCSVFile = aImageCSVFile;
		myMetadataCSVFile = aMetadataCSVFile;
		myThumbnailCSVFile = aThumbnailCSVFile;

        myManifestFile = aManifestFile;

		myManifestARK = aARKIdentifier;
        // TODO: bad magic number, because the 
        myManifestLabel = getCSVRowField(aMetadataCSVFile, aARKIdentifier, "Title");

        myServer = aServer;
        myIiifServer = aServer + SERVICE_PREFIX;

        if (aDimensions != null) {
            myWidth = Integer.parseInt(aDimensions.split(",")[0]);
            myHeight = Integer.parseInt(aDimensions.split(",")[1]);
        } else {
            myWidth = -1;
            myHeight = -1;
        }
	}

	public static void main(final String[] args) throws IOException, URISyntaxException, Exception {
		File cFile = null;
        File mFile = null;
		File tFile = null;
		File oFile = null;
		String ark = null;
        String server = null;
        String dimensions = null;

		if (args.length == 0) {
			LOGGER.warn("Manifestor started without any arguments");
			printUsageAndExit();
		}

		for (int index = 0; index < args.length; index++) {
			if (args[index].equals("-c")) {
				cFile = new File(args[++index]);

				if (!cFile.exists()) {
					LOGGER.error("Image CSV doesn't exist: {}", cFile);
					System.exit(1);
				} else if (cFile.exists() && !cFile.canRead()) {
					LOGGER.error("Image CSV exists but can't be read: {}", cFile);
					System.exit(1);
				}
			} else if (args[index].equals("-m")) {
				mFile = new File(args[++index]);

				if (!mFile.exists()) {
					LOGGER.error("Metadata CSV doesn't exist: {}", mFile);
					System.exit(1);
				} else if (mFile.exists() && !mFile.canRead()) {
					LOGGER.error("Metadata CSV exists but can't be read: {}", mFile);
					System.exit(1);
				}
			} else if (args[index].equals("-t")) {
				tFile = new File(args[++index]);

				if (!tFile.exists()) {
					LOGGER.error("Thumbnail CSV doesn't exist: {}", tFile);
					System.exit(1);
				} else if (tFile.exists() && !tFile.canRead()) {
					LOGGER.error("Thumbnail CSV exists but can't be read: {}", tFile);
					System.exit(1);
				}
			} else if (args[index].equals("-o")) {
				oFile = new File(args[++index]);

				if (oFile.exists() && !oFile.canWrite()) {
					LOGGER.error("Output manifest file exists and can't be overwritten: {}", oFile);
					System.exit(1);
				} else {
					final File parent = oFile.getParentFile();

					if (!parent.exists() && !parent.mkdirs()) {
						LOGGER.error("Manifest file's parent dir doesn't exist and can't be created: {}", parent);
						System.exit(1);
					}
				}
			} else if (args[index].equals("-d")) {
				dimensions = args[++index];

				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("Image dimensions: {}", dimensions);
				}
			} else if (args[index].equals("-a")) {
				ark = args[++index];

				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("Manuscript/manifest ARK: {}", ark);
				}
			} else if (args[index].equals("-s")) {
				final String arg = args[++index].toLowerCase();
                if (arg.equals("test")) {
                    server = TEST_SERVER;
                } else if (arg.equals("stage")) {
                    server = STAGE_SERVER;
                } else if (arg.equals("prod")) {
                    server = PROD_SERVER;
                } else {
                    LOGGER.error("Unknown server label: {}", arg);
                    System.exit(1);
                }

				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("Server: {}", server);
				}
			}
		}

		if (cFile == null || mFile == null ||  oFile == null || ark == null || server == null || (tFile == null && dimensions != null || tFile != null && dimensions == null)) {
			LOGGER.warn("Manifestor started without all the required arguments");
			printUsageAndExit();
		} else {
			final CSVManifestor manifestor = new CSVManifestor(cFile, mFile, tFile, oFile, ark, server, dimensions);
			manifestor.manifest(manifestor.new SinaiComparator());
		}
	}

	private void manifest(final SinaiComparator aComparator) throws IOException, URISyntaxException, Exception {
		final CSVReader csvReader = new CSVReader(new FileReader(myImageCSVFile));
		final List<String[]> sources = csvReader.readAll();
		final String thumbnailID = sources.get(0)[0]; // for now, just using
														// first image
		final Manifest manifest = getManifest(myManifestARK, thumbnailID, myManifestLabel);
		final List<Sequence> sequences = manifest.getSequences();

		// flag to let us know when to create a new canvas with images from startIndex to index
		String canvasName = "";

		// used to create the canvas @id field
		int canvasCount = 0;

		// used to create the image @id field
		int imagesCount = 0;

		// first position at which an image, that will go on the current canvas, exists
		int startIndex = 0;

        // copy of startIndex
        int canvasThumbnailIndex;

		// Get our sources in the order we want (so color images have
		// preference)
		Collections.sort(sources, aComparator);

		// only one sequence in the manifest
		if (sequences.add(getSequence(myManifestARK, 0))) {
			final List<Canvas> canvases = new ArrayList<Canvas>();

			for (int index = 0; index < sources.size();) {
				final String name = getCanvasName(sources.get(index)[1]);
				// check if we are at the end of the list
				if (!name.equals(canvasName) || index == sources.size() - 1) {
					if (!canvasName.equals("")) {
						final List<Image> images = new ArrayList<Image>();
						final ChoiceImageResource choiceImageResource = new ChoiceImageResource();
						ImageResource aResource;

						// be sure to include the last item if we are at the end of the list
						if (index == sources.size() - 1) {
							index++;
						}
						// build a ChoiceImageResource
                        canvasThumbnailIndex = startIndex;
						if (LOGGER.isDebugEnabled()) {
                            //LOGGER.debug("Starting at index: {}", index);
						}
						for (int count = 1; startIndex < index; startIndex++) {
							final String[] source = sources.get(startIndex);
						    if (LOGGER.isDebugEnabled()) {
                                //LOGGER.debug("    Source: {}", source[1]);
                            }

							// if count is 1, create a new resource under default
							aResource = getImageResource(source[0], source[1]);
							if (count == 1) {
								choiceImageResource.setDefault(aResource);
							}
							// if count is > 1, add a new resource to item
							else {
								choiceImageResource.addAltItem(aResource);
							}
							count++;
						}
						
						// code must be ordered this way, because canvas height can only be set using the constructor
						final Canvas canvas = getCanvas(myManifestARK, canvasName, ++canvasCount, ((ImageResource) choiceImageResource.getDefault()).getHeight(), ((ImageResource) choiceImageResource.getDefault()).getWidth());
						if (LOGGER.isDebugEnabled()) {
							LOGGER.debug("Processed canvas: {}", canvas.getId());
						}
						final String canvasId = canvas.getId();
						final Image image = getImage(myManifestARK, canvasId, ++imagesCount, choiceImageResource);
						images.add(image);

						canvas.setImages(images);

						final Service service = choiceImageResource.getDefault().getService();


						if (myThumbnailCSVFile != null) {
                            // TODO: bad magic number, because we know that the thumbnail CSV only has two rows
                            String firstThumbnail = sources.get(canvasThumbnailIndex)[0];
							try {
								canvas.setThumbnail(getCSVRow(myThumbnailCSVFile, firstThumbnail)[1]);
							} catch (Exception e) {
								if (e.getMessage() == "CSV Row Not Found") {
									LOGGER.error("Thumbnail not found in the CSV file for " + firstThumbnail);
									System.exit(1);
								}
								throw e;
							}
						}
						else {
							canvas.setThumbnail(getThumbnail(getBareID(service.getId())));
						}
						canvases.add(canvas);
					}

					canvasName = name;
					startIndex = index;
				}
				index++;
			}

			sequences.get(sequences.size() - 1).setCanvases(canvases);
		}

		org.codehaus.plexus.util.FileUtils.fileWrite(myManifestFile, "UTF-8", toJson(manifest));
		csvReader.close();
	}

	/**
	 * Go through the given CSV and find the value that corresponds to the given ARK.
     *
     * @return {String} value of the field in the matching row, or empty string if no matching row
	 */
	private final String getCSVRowField(final File aFile, final String aARK, final String aColumn) throws IOException {

		final CSVReader aCsvReader = new CSVReader(new FileReader(aFile));
		final List<String[]> sources = aCsvReader.readAll();

		String[] source;
		for (int index = 1; index < sources.size(); index++) {
			source = sources.get(index);
			if (source[0].equals(aARK)) {
                final String[] header = sources.get(0);
                final int headerIndex = Arrays.asList(header).indexOf(aColumn);
				aCsvReader.close();
                if (headerIndex != -1) {
                    return source[headerIndex];
                } else {
                    return "";
                }
			}
		}
		aCsvReader.close();
        // assume that no fields will be empty, so empty string can serve as error flag
		return "";
	}

	/**
	 * Go through the given CSV and find the value that corresponds to the given ARK.
     *
     * @return {Array} that represents the row, if one is found
	 */
	private final String[] getCSVRow(final File aFile, final String aARK) throws IOException, Exception {

		final CSVReader aCsvReader = new CSVReader(new FileReader(aFile));
		final List<String[]> sources = aCsvReader.readAll();
		String[] source;
		for (int index = 0; index < sources.size(); index++) {
			source = sources.get(index);
			if (source[0].equals(aARK)) {
				aCsvReader.close();
				return source;
			}
		}
		aCsvReader.close();
		throw new Exception("CSV Row Not Found");
	}

	/**
	 * Returns an object that will go into a canvas's 'images' array.
	 *
	 * @param {String} aObjID       ARK of the manifest, used to generate the image's '@id'
	 * @param {string} aOn          A string that identifies the parent canvas
	 * @param {int} aCount          A number that uniquely identifies this image within the canvas
	 * @param {ChoiceImageResource} aCIR
	 * @return {Image}
	 */
	private final Image getImage(final String aObjID, final String aOn, final int aCount, final ChoiceImageResource aChoiceImageResource) throws URISyntaxException, MalformedURLException, IOException {
		final String imageId = getID(myIiifServer, PathUtils.encodeIdentifier(aObjID), "imageanno", aCount);

		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("Processing image: {}", imageId);
		}

		final Image image = new Image(imageId);
		image.setOn(aOn);
		image.setResource((Resource) aChoiceImageResource);
		return image;
	}

	/**
	 * Returns an object that will go into a choice image's 'default' key, or into the array under its 'item' key.
	 *
	 * @param {String} aImageID ARK of the image
	 * @param {String} aLabel   A string (file path usually) from which to generate the resource's 'label'
	 * @return {ImageResourceWithLabelAndThumbnail} (casted as ImageResource)
	 */
	private final ImageResource getImageResource(final String aImageID, final String aLabel) throws URISyntaxException, MalformedURLException, IOException, Exception {
		final String label = FileUtils.stripExt(new File(aLabel));

		final ImageResourceWithLabelAndThumbnail resource = new ImageResourceWithLabelAndThumbnail();
		final Service service = new Service(myIiifServer + PathUtils.encodeIdentifier(aImageID));
		final Dimension dims = getHeightWidth(aImageID);

		service.setProfile(IIIF_PROFILE);
		service.setContext(IIIF_CONTEXT);
		resource.setHeight(dims.height);
		resource.setWidth(dims.width);
		resource.setFormat("image/jpeg");
		resource.setService(service);
		resource.setLabel(label);
		if (myThumbnailCSVFile != null) {
            // TODO: bad magic number, because we know that the thumbnail CSV only has two rows
			try {
				resource.setThumbnail(getCSVRow(myThumbnailCSVFile, aImageID)[1]);
			} catch (Exception e) {
				if (e.getMessage() == "CSV Row Not Found") {
					LOGGER.error("Thumbnail not found in the CSV file for " + aImageID);
					System.exit(1);
				}
				throw e;
			}
        } else {
            // get from solr
            resource.setThumbnail(getThumbnail(getBareID(service.getId())));
        }

		return (ImageResource) resource;
	}

	/**
	 * Returns a canvas object.
	 *
	 * @param {String} aID     ARK of the containing manifest
	 * @param {String} aLabel  Name for the canvas
	 * @param {int} aCount     Number that uniquely identifies this canvas in the sequence
	 * @param {int} aHeight    Height of the canvas
	 * @param {int} aWidth     Width of the canvas
	 * @return {Canvas}
	 */
	private final Canvas getCanvas(final String aID, final String aLabel, final int aCount, final int aHeight, final int aWidth)
			throws URISyntaxException, MalformedURLException, IOException {
		final String id = getID(myIiifServer, PathUtils.encodeIdentifier(aID), "canvas", aCount);
		final Canvas canvas = new Canvas(id, aLabel, aHeight, aWidth);

		return canvas;
	}

	private final String getBareID(final String aURI) {
		return aURI.substring(aURI.indexOf(SERVICE_PREFIX) + SERVICE_PREFIX.length()).replace("/info.json", "");
	}

	private final Dimension getHeightWidth(final String aImageID)
			throws URISyntaxException, MalformedURLException, IOException {

        // get from info.json
        if (myThumbnailCSVFile == null) {
		final URL url = new URL(myIiifServer + PathUtils.encodeIdentifier(aImageID) + "/info.json");
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
        } else {

        // use the following if there doesn't exist an info.json yet
		final Dimension dimension = new Dimension();
        // TODO: bad magic numbers
		dimension.setSize(myWidth, myHeight);

		return dimension;
        }
	}

	private final Sequence getSequence(final String aID, final int aCount) throws URISyntaxException {
		final Sequence sequence = new Sequence();

		sequence.setId(getID(myIiifServer, PathUtils.encodeIdentifier(aID), "sequence", aCount));
		sequence.setLabel(aID);

		return sequence;
	}

	private final Manifest getManifest(final String aID, final String aThumbnail, final String aLabel)
			throws URISyntaxException, IOException, Exception {
		final Manifest manifest = new Manifest(myIiifServer + PathUtils.encodeIdentifier(aID) + "/manifest", aLabel);

		manifest.setLogo(myServer + "/images/logos/iiif_logo.png");
		if (myThumbnailCSVFile != null) {
            // TODO: bad magic number, because we know that the thumbnail CSV only has two rows
			try {
				manifest.setThumbnail(getCSVRow(myThumbnailCSVFile, aThumbnail)[1]);
			} catch (Exception e) {
				if (e.getMessage() == "CSV Row Not Found") {
					LOGGER.error("Thumbnail not available for this manuscript");
					System.exit(1);
				}
				throw e;
			}
        // get from solr
		} else {
			manifest.setThumbnail(getThumbnail(aThumbnail));
		}
		manifest.setSequences(new ArrayList<Sequence>());
        manifest.setMetadata(getManifestMetadata(aID));

		return manifest;
	}

    private final ArrayList<Metadata> getManifestMetadata(final String aID) throws IOException, Exception {
		final CSVReader aCsvReader = new CSVReader(new FileReader(myMetadataCSVFile));
		final List<String[]> sources = aCsvReader.readAll();

        ArrayList<Metadata> aMetadata = new ArrayList<Metadata>();
        String[] CSVHeaders = sources.get(0);
        String[] CSVRow;
		try {
			CSVRow = getCSVRow(myMetadataCSVFile, aID);
		} catch (Exception e) {
			if (e.getMessage() == "CSV Row Not Found") {
				LOGGER.error("Metadata info not found in the CSV file.");
				System.exit(1);
			}
			throw e;
		}

        if (CSVHeaders.length == CSVRow.length) {
            for (int i = 1; i < CSVHeaders.length; i++) {
                aMetadata.add( new MetadataSimpleWithGetter(CSVHeaders[i], CSVRow[i]));
            }
        }
        return aMetadata;
    }

	private final String getThumbnail(final String aID) throws URISyntaxException, MalformedURLException, IOException {

		final String solrTemplate = myServer.replace("https", "http") + ":8983/solr/jiiify/select?q=\"{}\"&wt=json";
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

		// We're not doing any exception catching here... we expect it to be
		// there or else we fail
		thumbnail = json.getJsonObject("response").getJsonArray("docs").getJsonObject(0)
				.getString("jiiify_thumbnail_s");

		return myIiifServer + thumbnail.replace("/iiif/", "");
	}

	private final String getCanvasName(final String aImagePath) {
		final String[] parts = aImagePath.split("\\/");

		// check if this tif CSV uses the 'default_includes' scheme
		if (parts[parts.length - 2].equals("default_includes")) {
			return parts[parts.length - 3];
		} else {
			return parts[parts.length - 2];
		}
	}

	private final String getID(final String aHost, final String aID, final String aType, final int aCount) {
		final StringBuilder builder = new StringBuilder(aHost).append(aID).append('/').append(aType);
		return builder.append('/').append(aType).append("-").append(aCount).toString();
	}

	private final String toJson(final Manifest manifest) throws JsonProcessingException {
		final ObjectMapper mapper = new ObjectMapper();

		mapper.addMixIn(AbstractIiifResource.class, AbstractIiifResourceMixIn.class);
		mapper.addMixIn(Image.class, AbstractIiifResourceMixIn.class);
		mapper.addMixIn(Manifest.class, ManifestMixIn.class);
		mapper.addMixIn(MetadataLocalizedValue.class, MetadataLocalizedValueMixIn.class);
		mapper.addMixIn(Resource.class, AbstractIiifResourceMixIn.class);
		mapper.addMixIn(Service.class, ServiceMixIn.class);
		mapper.addMixIn(ChoiceImageResource.class, AbstractIiifResourceMixIn.class);
		mapper.addMixIn(MetadataSimpleWithGetter.class, AbstractIiifResourceMixIn.class);
		mapper.setSerializationInclusion(Include.NON_NULL);

		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
	}

	/**
	 * Comparator that sorts by file name except when one of the file names ends
	 * with _color.tiff, then that's given preference.
	 *
	 * @author Kevin S. Clarke
	 *         <a href="mailto:ksclarke@ksclarke.io">ksclarke@ksclarke.io</a>
	 */
	class SinaiComparator implements Comparator<String[]> {

		public int compare(final String[] a1stSource, final String[] a2ndSource) {
            //LOGGER.info("***Comparator: {} {}", a1stSource[1], a2ndSource[1]);
			final String[] splits1 = a1stSource[1].split("/");
			final String[] splits2 = a2ndSource[1].split("/");
			final String fileName1 = splits1[splits1.length - 1];
			final String fileName2 = splits2[splits2.length - 1];
			final String colorTIFF = "_color.tif";
            final int comparisonResult;

			String dirName1 = splits1[splits1.length - 2];
			String dirName2 = splits2[splits2.length - 2];

			// check if this tif CSV uses the 'default_includes' scheme
            //LOGGER.info("{} {}", dirName1, dirName2);
			if (dirName1.equals("default_includes")) {
				dirName1 = splits1[splits1.length - 3];
			}
			if (dirName2.equals("default_includes")) {
				dirName2 = splits2[splits2.length - 3];
			}

			if (dirName1.equals(dirName2)) {
				if (fileName1.endsWith(colorTIFF)) {
                    //LOGGER.info("***            {} before {}", a1stSource[1], a2ndSource[1]);
					return -1;
				} else if (fileName2.endsWith(colorTIFF)) {
                    //LOGGER.info("***            {} after {}", a1stSource[1], a2ndSource[1]);
					return 1;
				} else {
					comparisonResult = fileName1.compareToIgnoreCase(fileName2);
                    //LOGGER.info("***            {}" + (comparisonResult < 0 ? " before " : (comparisonResult == 0 ? " same " : " after ")) + "{}", fileName1, fileName2);
                    return comparisonResult;
				}
			} else {
		        comparisonResult = dirName1.compareToIgnoreCase(dirName2);
                //LOGGER.info("***Comparator: {} {}", a1stSource[1], a2ndSource[1]);
                //LOGGER.info("***            {}" + (comparisonResult < 0 ? " before " : (comparisonResult == 0 ? " same " : " after ")) + "{}", a1stSource[1], a2ndSource[1]);
				return comparisonResult;
			}
		}
	}

	private static final void printUsageAndExit() {
		System.err.println();
		System.err.println("Usage:\n    -c [path to source CSV]\n    -m [path to metadata CSV]\n    -t [path to thumbnail CSV]\n    -o [path to output manifest]\n    -a [Archival Resource Key]\n    -s [server (test, stage, prod)]\n    -d [dimensions (width,height)]\n");
		System.err.println("    -t and -d must both be either present or absent\n");
		System.err.println(
				"For example: java -jar ezid-crawler.jar -c \"/home/kevin/syriac-filtered.csv\" -m \"/home/kevin/manifest-metadata.csv\" -t \"/home/kevin/first-five-thumbnails.csv\" -o \"/home/kevin/manifest.json\" -a \"ark:/21198/z1h70g33\" -s \"stage\" -d \"8000,6000\"");
		System.err.println();
		System.exit(1);
	}
}
