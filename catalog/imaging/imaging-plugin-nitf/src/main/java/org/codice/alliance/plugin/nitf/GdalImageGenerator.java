package org.codice.alliance.plugin.nitf;

import com.google.common.io.Files;
import ddf.catalog.Constants;
import ddf.catalog.content.data.ContentItem;
import java.awt.Dimension;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.codice.imaging.nitf.core.common.NitfFormatException;
import org.codice.imaging.nitf.core.image.ImageBand;
import org.codice.imaging.nitf.core.image.ImageRepresentation;
import org.codice.imaging.nitf.fluent.impl.NitfParserInputFlowImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GdalImageGenerator implements ImageGenerator {

  private static final Pattern STATS_PATTERN = Pattern.compile(
      "(?s).*Minimum=(\\d+).+Maximum=(\\d+).+Mean=(\\d+(\\.\\d+)?).+StdDev=(\\d+(\\.\\d+)?).*");

  private static final Logger LOGGER = LoggerFactory.getLogger(GdalImageGenerator.class);

  private ContentItem contentItem;
  private Map<String, Serializable> properties;
  private ImageRepresentation imageRepresentation;
  private List<ImageBand> imageBands;
  private ImageStatistics imageStatistics;

  private File imageFile;

  @Override
  public void setImageSource(ContentItem contentItem, Map<String, Serializable> properties) {
    this.contentItem = contentItem;
    this.properties = properties;
  }

  private boolean resolveSource() {

    Map<String, Map<String, Path>> tmpContentPaths =
        (Map<String, Map<String, Path>>) properties.get(Constants.CONTENT_PATHS);

    Map<String, Path> contentPaths = tmpContentPaths.get(contentItem.getId());
    if (contentPaths == null || contentPaths.isEmpty()) {
      LOGGER
          .warn("No path for ContentItem (id={}). Unable to create thumbnail for metacard (id={})",
              contentItem.getId(),
              contentItem.getMetacard()
                  .getId());
      return false;
    }

    // create a thumbnail for the unqualified content item
    Path tmpPath = contentPaths.get(null);
    if (tmpPath == null) {
      LOGGER.warn(
          "Cannot find temp path for ContentItem (id={}). Unable to create thumbnail for metacard (id={})",
          contentItem.getId(),
          contentItem.getMetacard()
              .getId());
      return false;
    }

    imageFile = tmpPath.toFile();

    try {
      imageBands = new ArrayList<>();
      List<ImageRepresentation> imageReps = new ArrayList<>();

      new NitfParserInputFlowImpl().inputStream(contentItem.getInputStream())
          .headerOnly()
          .forEachImageSegment(segment -> {
            if (imageBands.size() == 0) {
              imageReps.add(segment.getImageRepresentation());
              for (int i = 0; i < segment.getNumBands(); i++) {
                imageBands.add(segment.getImageBandZeroBase(i));
              }
            }
          }).end();

      imageRepresentation = imageReps.size() > 0 ? imageReps.get(0) : null;
    } catch (IOException | NitfFormatException e) {
      LOGGER.debug("Unable to parse NITF", e);
      return false;
    }

    try {
      imageStatistics = imageRepresentation == ImageRepresentation.MONOCHROME ? getImageStatistics(
          imageFile) : null;
    } catch (IOException | InterruptedException e) {
      LOGGER.debug("Unable to get image statistics for image " + imageFile, e);
    }

    return true;
  }

  @Override
  public ImageSource createImage(Dimension imageDimensions, OutputFormat outputFormat)
      throws IOException {
    if (imageFile == null) {
      if (!resolveSource()) {
        throw new IOException("Unable to read source data");
      }
    }

    try {
      List<String> command = createCommand(imageDimensions,
          outputFormat, imageBands, imageRepresentation, imageStatistics);

      command.add(StringUtils.wrap(imageFile.getAbsolutePath(), "\""));

      String filename = imageFile.getName();
      if (filename.contains(".")) {
        filename = filename.substring(0, filename.lastIndexOf("."));
      }
      filename = UUID.randomUUID() + "_" + filename + "." + outputFormat.getExtension();
      File outFile = new File(imageFile.getParentFile(), filename);
      command.add(StringUtils.wrap(outFile.getAbsolutePath(), "\""));

      String output = null;
      try {
        output = runCommand(command);
      } catch (Exception e) {
        outFile.delete();
        throw new IOException("Unable to create image", e);
      }

      if(!outFile.exists()) {
        throw new IOException("Output image not generated correctly:\n" + output);
      }

      return new ImageSource(Files.asByteSource(outFile), outFile.length());
    } catch (InterruptedException e) {
      throw new IOException("Unable to execute GDAL translation", e);
    }
  }

  private List<String> createCommand(Dimension imageDimensions, OutputFormat outputFormat,
      List<ImageBand> imageBands, ImageRepresentation imageRep, ImageStatistics imageStatistics)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add("gdal_translate");

    command.add("-of");
    if (outputFormat == null) {
      outputFormat = OutputFormat.JPEG;
    }

    switch (outputFormat) {
      case JPEG:
        command.add("JPEG");
        break;
      case JPEG_2000:
        command.add("JP2OpenJPEG");
        break;
      default:
        throw new IllegalArgumentException("Unsupported output format: " + outputFormat.name());
    }

    if (imageBands != null && imageBands.size() > 1) {
      int redIndex = -1;
      int greenIndex = -1;
      int blueIndex = -1;

      int bandIndex = 1;
      for (ImageBand band : imageBands) {
        String irep = band.getImageRepresentation();
        if (irep.equals("R")) {
          redIndex = bandIndex;
        } else if (irep.equals("G")) {
          greenIndex = bandIndex;
        } else if (irep.equals("B")) {
          blueIndex = bandIndex;
        }

        bandIndex++;
      }

      if (redIndex != -1) {
        command.add("-b");
        command.add(Integer.toString(redIndex));
      }
      if (greenIndex != -1) {
        command.add("-b");
        command.add(Integer.toString(greenIndex));
      }
      if (blueIndex != -1) {
        command.add("-b");
        command.add(Integer.toString(blueIndex));
      }
    }

    boolean byteAdded = false;
    if (outputFormat == OutputFormat.JPEG) {
      command.add("-ot");
      command.add("Byte");
      byteAdded = true;
    }

    if (imageRep != null && imageRep == ImageRepresentation.MONOCHROME && imageStatistics != null) {

      int[] scaleRange = imageStatistics.getScaleRange();

      if (scaleRange != null && scaleRange.length == 2) {
        if (!byteAdded) {
          command.add("-ot");
          command.add("Byte");
        }
        command.add("-scale");
        command.add(Integer.toString(scaleRange[0]));
        command.add(Integer.toString(scaleRange[1]));
      }
    }

    command.add("-outsize");
    command.add(Integer.toString(imageDimensions.width));
    command.add(Integer.toString(imageDimensions.height));

    return command;
  }

  private String runCommand(List<String> command) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process process = pb.start();

    BufferedReader stdOut = null;
    try {
      stdOut = new BufferedReader(new InputStreamReader(process.getInputStream()));
      String output = stdOut.lines().collect(Collectors.joining("\n"));

      LOGGER.debug(StringUtils.join(pb.command(), " "));
      LOGGER.debug(output);

      int exitCode = process.waitFor();

      stdOut.close();

      if (exitCode != 0) {
        throw new RuntimeException("Command failed with code: " + exitCode + "\n" + output);
      }

      return output;
    }
    finally {
      if(stdOut != null)
        stdOut.close();
    }
  }

  private ImageStatistics getImageStatistics(File file) throws IOException, InterruptedException {
    List<String> infoCommand = new ArrayList<>();
    infoCommand.add("gdalinfo");
    infoCommand.add("-stats");
    infoCommand.add(file.getAbsolutePath());

    String stats = runCommand(infoCommand);

    ImageStatistics statistics = null;

    Matcher matcher = STATS_PATTERN.matcher(stats);
    if (matcher.matches()) {
      statistics = new ImageStatistics();
      statistics.minPixelValue = Double.valueOf(matcher.group(1));
      statistics.maxPixelValue = Double.valueOf(matcher.group(2));
      statistics.meanPixelValue = Double.valueOf(matcher.group(3));
      statistics.standardDeviation = Double.valueOf(matcher.group(5));
    }

    return statistics;
  }

  class ImageStatistics {

    double minPixelValue;
    double maxPixelValue;
    double meanPixelValue;
    double standardDeviation;

    int[] getScaleRange() {

      double minDeviations = ((meanPixelValue - minPixelValue) / standardDeviation);
      double maxDeviations = ((maxPixelValue - meanPixelValue) / standardDeviation);

      double deviations = Math.ceil(Math.min(minDeviations, maxDeviations));
      double minValue = Math.max(minPixelValue, (meanPixelValue - deviations * standardDeviation));
      double maxValue = Math.min(maxPixelValue, (meanPixelValue + deviations * standardDeviation));

      return new int[]{(int) Math.floor(minValue), (int) Math.ceil(maxValue)};
    }
  }
}
