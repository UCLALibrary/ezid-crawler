
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

import com.fasterxml.jackson.annotation.JsonProperty;

class ChoiceImageResource extends Resource {

	protected Resource myDefault;
	protected List<Resource> myItem;

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

class ImageResourceWithLabel extends ImageResource {
	protected String myLabel;
	public String getLabel() {
		return myLabel;
	}
	public void setLabel(final String aLabel) {
		myLabel = aLabel;
	}
}

/*
class CanvasFixed extends Canvas {
	public CanvasFixed(final String aID, final String aLabel, final int aHeight, final int aWidth) {

		super(aID, aLabel, aHeight, aWidth);
	}
	public void setHeight(final int aHeight) {
		myHeight = aHeight;
	}
}
*/

abstract class ChoiceImageResourceMixIn {
	@JsonProperty("@type")
	abstract String getType();
}

/*
class ChoiceImage extends Image {
	protected ChoiceImageResource myChoiceImageResource;
	public ChoiceImageResource getChoiceImageResource() {
		return myChoiceImageResource;
	}
}
*/

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

	private static final String SERVER = "https://stage-images.library.ucla.edu";

	private static final String SERVICE_PREFIX = "/iiif/";

	private static final String IIIF_SERVER = SERVER + SERVICE_PREFIX;

	private static final String IIIF_PROFILE = "http://iiif.io/api/image/2/level0.json";

	private static final String IIIF_CONTEXT = "http://iiif.io/api/image/2/context.json";

	private final String myManifestARK;

	private final File myCSVFile;

	private final File myManifestFile;

	private final File myThumbnailCSVFile;

	public CSVManifestor(final File aCSVFile, final File aManifestFile, final String aARKIdentifier, final File aThumbnailCSVFile) {
		myManifestARK = aARKIdentifier; /* ark:/21198/z1h70g33 */
		myCSVFile = aCSVFile; /* /home/kevin/syriac-filtered.csv */
		myManifestFile = aManifestFile; /* /home/kevin/syriac-manifest.json */
		myThumbnailCSVFile = aThumbnailCSVFile;
	}

	public static void main(final String[] args) throws IOException, URISyntaxException {
		File cFile = null;
		File mFile = null;
		String ark = null;
		File tFile = null;

		if (args.length == 0) {
			LOGGER.warn("Manifestor started without any arguments");
			printUsageAndExit();
		}

		for (int index = 0; index < args.length; index++) {
			if (args[index].equals("-c")) {
				cFile = new File(args[++index]);

				if (!cFile.exists()) {
					LOGGER.error("Source CSV doesn't exist: {}", cFile);
					System.exit(1);
				} else if (cFile.exists() && !cFile.canRead()) {
					LOGGER.error("Source CSV exists but can't be read: {}", cFile);
					System.exit(1);
				}
			} else if (args[index].equals("-m")) {
				mFile = new File(args[++index]);

				if (mFile.exists() && !mFile.canWrite()) {
					LOGGER.error("Output manifest file exists and can't be overwritten: {}", mFile);
					System.exit(1);
				} else {
					final File parent = mFile.getParentFile();

					if (!parent.exists() && !parent.mkdirs()) {
						LOGGER.error("Manifest file's parent dir doesn't exist and can't be created: {}", parent);
						System.exit(1);
					}
				}
			} else if (args[index].equals("-a")) {
				ark = args[++index];

				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("Manuscript/manifest ARK: {}", ark);
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
			}
		}

		if (cFile == null || mFile == null || ark == null) {
			LOGGER.warn("Manifestor started without all the required arguments");
			printUsageAndExit();
		} else {
			final CSVManifestor manifestor = new CSVManifestor(cFile, mFile, ark, tFile);
			manifestor.manifest(manifestor.new SinaiComparator());
		}
	}

	private void manifest(final SinaiComparator aComparator) throws IOException, URISyntaxException {
		final CSVReader csvReader = new CSVReader(new FileReader(myCSVFile));
		final List<String[]> sources = csvReader.readAll();
		final String thumbnailID = sources.get(0)[0]; // for now, just using
														// first image
		final Manifest manifest = getManifest(myManifestARK, thumbnailID);
		final List<Sequence> sequences = manifest.getSequences();

		// flag to let us know when to create a new canvas with images from startIndex to index
		String canvasName = "";

		// used to create the canvas @id field
		int canvasCount = 0;

		// used to create the image @id field
		int imagesCount = 0;

		// first position at which an image, that will go on the current canvas, exists
		int startIndex = 0;

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
						for (int count = 1; startIndex < index; startIndex++) {
							final String[] source = sources.get(startIndex);

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
							// TODO: fix
							canvas.setThumbnail(searchForThumbnailURI(sources.get(0)[0]));
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
	 * Go through the thumbnail CSV and find the URI for the ARK.
	 */
	private final String searchForThumbnailURI(final String aARK) throws IOException {

		final CSVReader thumbnailCsvReader = new CSVReader(new FileReader(myThumbnailCSVFile));
		final List<String[]> sources = thumbnailCsvReader.readAll();
		String[] source;
		for (int index = 0; index < sources.size(); index++) {
			source = sources.get(index);
			if (source[0].equals(aARK)) {
				thumbnailCsvReader.close();
				return source[1];
			}
		}
		thumbnailCsvReader.close();
		return "";
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
		final String imageId = getID(IIIF_SERVER, PathUtils.encodeIdentifier(aObjID), "imageanno", aCount);

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
	 * @return {ImageResourceWithLabel} (casted as ImageResource)
	 */
	private final ImageResource getImageResource(final String aImageID, final String aLabel) throws URISyntaxException, MalformedURLException, IOException {
		final String label = FileUtils.stripExt(new File(aLabel));

		final ImageResourceWithLabel resource = new ImageResourceWithLabel();
		final Service service = new Service(IIIF_SERVER + PathUtils.encodeIdentifier(aImageID));
		final Dimension dims = getHeightWidth(aImageID);

		service.setProfile(IIIF_PROFILE);
		service.setContext(IIIF_CONTEXT);
		resource.setHeight(dims.height);
		resource.setWidth(dims.width);
		resource.setFormat("image/jpeg");
		resource.setService(service);
		resource.setLabel(label);

		return (ImageResource) resource;
	}

	/**
	 * Returns a canvas object.
	 *
	 * @param {Stringg} aID     ARK of the containing manifest
	 * @param {String} aLabel   Name for the canvas
	 * @param {int} aCount      Number that uniquely identifies this canvas in the sequence
	 * @param {int} aHeight     Height of the canvas
	 * @param {int} aWidth      Width of the canvas
	 * @return {Canvas}
	 */
	private final Canvas getCanvas(final String aID, final String aLabel, final int aCount, final int aHeight, final int aWidth)
			throws URISyntaxException, MalformedURLException, IOException {
		final String id = getID(IIIF_SERVER, PathUtils.encodeIdentifier(aID), "canvas", aCount);
		final Canvas canvas = new Canvas(id, aLabel, aHeight, aWidth);

		return canvas;
	}

	private final String getBareID(final String aURI) {
		return aURI.substring(aURI.indexOf(SERVICE_PREFIX) + SERVICE_PREFIX.length()).replace("/info.json", "");
	}

	private final Dimension getHeightWidth(final String aImageID)
			throws URISyntaxException, MalformedURLException, IOException {
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

/*
        // use the following if there doesn't exist an info.json yet
		final Dimension dimension = new Dimension();
		dimension.setSize(8176, 6132);

		return dimension;
*/
	}

	private final Sequence getSequence(final String aID, final int aCount) throws URISyntaxException {
		final Sequence sequence = new Sequence();

		sequence.setId(getID(IIIF_SERVER, PathUtils.encodeIdentifier(aID), "sequence", aCount));
		sequence.setLabel(aID);

		return sequence;
	}

	private final Manifest getManifest(final String aID, final String aThumbnail)
			throws URISyntaxException, IOException {
		final Manifest manifest = new Manifest(IIIF_SERVER + PathUtils.encodeIdentifier(aID) + "/manifest", aID);

		manifest.setLogo(SERVER + "/images/logos/iiif_logo.png");
		if (myThumbnailCSVFile != null) {
			manifest.setThumbnail(searchForThumbnailURI(aThumbnail));
		} else {
			manifest.setThumbnail(getThumbnail(aThumbnail));
		}
		manifest.setSequences(new ArrayList<Sequence>());

		return manifest;
	}

	private final String getThumbnail(final String aID) throws URISyntaxException, MalformedURLException, IOException {

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

		// We're not doing any exception catching here... we expect it to be
		// there or else we fail
		thumbnail = json.getJsonObject("response").getJsonArray("docs").getJsonObject(0)
				.getString("jiiify_thumbnail_s");

		return IIIF_SERVER + thumbnail.replace("/iiif/", "");
	}

	private final String getCanvasName(final String aImagePath) {
		final String[] parts = aImagePath.split("\\/");
		return parts[parts.length - 2];
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

	private static final void printUsageAndExit() {
		System.err.println();
		System.err.println("Usage: -c [path to source CSV] -m [path to output manifest] -a [Archival Resource Key]");
		System.err.println(
				"  For example: java -jar ezid-crawler.jar -c \"/home/kevin/syriac-filtered.csv\" -m \"/home/kevin/syriac-manifest.json\" -a \"ark:/21198/z1h70g33\"");
		System.err.println();
		System.exit(1);
	}
}
