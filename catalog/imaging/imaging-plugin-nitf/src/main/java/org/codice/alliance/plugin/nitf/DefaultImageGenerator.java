package org.codice.alliance.plugin.nitf;

import com.github.jaiimageio.jpeg2000.J2KImageWriteParam;
import com.github.jaiimageio.jpeg2000.impl.J2KImageWriter;
import com.github.jaiimageio.jpeg2000.impl.J2KImageWriterSpi;
import com.google.common.io.ByteSource;
import ddf.catalog.content.data.ContentItem;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.text.ParseException;
import java.util.Map;
import java.util.function.Function;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.codice.imaging.nitf.core.common.NitfFormatException;
import org.codice.imaging.nitf.core.image.ImageSegment;
import org.codice.imaging.nitf.fluent.impl.NitfParserInputFlowImpl;
import org.codice.imaging.nitf.render.NitfRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultImageGenerator implements ImageGenerator {

  private static final int ARGB_COMPONENT_COUNT = 4;

  private static final long MEGABYTE = 1024L * 1024L;

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultImageGenerator.class);

  private int maxNitfSizeMB = 120;

  private ContentItem contentItem;
  private BufferedImage renderedImage;

  public DefaultImageGenerator(int maxNitfSizeMB) {
    this.maxNitfSizeMB = maxNitfSizeMB;
  }

  public void setMaxNitfSizeMB(int maxNitfSizeMB) {
    this.maxNitfSizeMB = maxNitfSizeMB;
  }

  @Override
  public void setImageSource(ContentItem contentItem, Map<String, Serializable> properties) {
    this.contentItem = contentItem;
  }

  @Override
  public ImageSource createImage(Dimension imageDimensions, OutputFormat outputFormat)
      throws IOException {
    if (renderedImage == null) {
      if (!loadImage()) {
        throw new IOException("Unable to read image");
      }
    }

    return scaleImage(renderedImage, imageDimensions.width, imageDimensions.height, outputFormat);
  }

  private boolean loadImage() throws IOException {
    if (contentItem.getSize() / MEGABYTE > maxNitfSizeMB) {
      LOGGER.debug("Skipping large ({} MB) content item: filename={}",
          contentItem.getSize() / MEGABYTE,
          contentItem.getFilename());
      return false;
    }

    try {
      renderedImage = renderImageUsingOriginalDataModel(contentItem);
    } catch (ParseException | NitfFormatException e) {
      throw new IOException("Error reading file", e);
    }

    return true;
  }

  private BufferedImage renderImage(ContentItem contentItem)
      throws IOException, ParseException, NitfFormatException {

    return render(contentItem, input -> {
      try {
        return input.getRight()
            .render(input.getLeft());
      } catch (IOException e) {
        LOGGER.debug(e.getMessage(), e);
      }
      return null;
    });

  }

  private BufferedImage renderImageUsingOriginalDataModel(ContentItem contentItem)
      throws IOException, ParseException, NitfFormatException {

    return render(contentItem, input -> {
      try {
        return input.getRight()
            .renderToClosestDataModel(input.getLeft());
      } catch (IOException e) {
        LOGGER.debug(e.getMessage(), e);
      }
      return null;
    });

  }

  private BufferedImage render(ContentItem contentItem,
      Function<Pair<ImageSegment, NitfRenderer>, BufferedImage> imageSegmentFunction)
      throws IOException, ParseException, NitfFormatException {

    final ThreadLocal<BufferedImage> bufferedImage = new ThreadLocal<>();

    if (contentItem != null) {
      InputStream inputStream = contentItem.getInputStream();

      if (inputStream != null) {
        try {
          NitfRenderer renderer = getNitfRenderer();

          new NitfParserInputFlowImpl().inputStream(inputStream)
              .allData()
              .forEachImageSegment(segment -> {
                if (bufferedImage.get() == null) {
                  BufferedImage bi =
                      imageSegmentFunction.apply(new ImmutablePair<>(segment,
                          renderer));
                  if (bi != null) {
                    bufferedImage.set(bi);
                  }
                }
              })
              .end();
        } finally {
          IOUtils.closeQuietly(inputStream);
        }
      }
    }

    return bufferedImage.get();
  }

  private ImageSource scaleImage(final BufferedImage bufferedImage, int width, int height,
      OutputFormat outputFormat)
      throws IOException {

    BufferedImage image = bufferedImage;
    if (width != bufferedImage.getWidth() || height != bufferedImage.getHeight()) {
      image = Thumbnails.of(bufferedImage)
          .size(width, height)
//        .outputFormat("jpg")
          .imageType(BufferedImage.TYPE_3BYTE_BGR)
          .asBufferedImage();
    }

    byte[] imageBytes = renderImage(image, outputFormat);

    if (imageBytes == null) {
      throw new IOException("Unable to scale image");
    }

    return new ImageSource(ByteSource.wrap(imageBytes), imageBytes.length);
  }

  private byte[] renderImage(final BufferedImage bufferedImage, OutputFormat outputFormat)
      throws IOException {
    byte[] imageBytes = null;
    if (outputFormat == OutputFormat.JPEG) {
      imageBytes = renderToJpeg(bufferedImage);
    } else if (outputFormat == OutputFormat.JPEG_2000) {
      imageBytes = renderToJpeg2k(bufferedImage);
    }

    return imageBytes;
  }

  private byte[] renderToJpeg(final BufferedImage bufferedImage) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    ImageIO.write(bufferedImage, "jpg", outputStream);
    outputStream.flush();
    byte[] thumbnailBytes = outputStream.toByteArray();
    outputStream.close();
    return thumbnailBytes;
  }

  private byte[] renderToJpeg2k(final BufferedImage bufferedImage) throws IOException {

    BufferedImage imageToCompress = bufferedImage;

    if (bufferedImage.getColorModel()
        .getNumComponents() == ARGB_COMPONENT_COUNT) {

      imageToCompress = new BufferedImage(bufferedImage.getWidth(),
          bufferedImage.getHeight(),
          BufferedImage.TYPE_3BYTE_BGR);

      Graphics2D g = imageToCompress.createGraphics();

      g.drawImage(bufferedImage, 0, 0, null);
    }

    ByteArrayOutputStream os = new ByteArrayOutputStream();

    J2KImageWriter writer = new J2KImageWriter(new J2KImageWriterSpi());
    J2KImageWriteParam writeParams = (J2KImageWriteParam) writer.getDefaultWriteParam();
    writeParams.setLossless(false);
    writeParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
    writeParams.setCompressionType("JPEG2000");
    writeParams.setCompressionQuality(0.0f);

    ImageOutputStream ios = new MemoryCacheImageOutputStream(os);
    writer.setOutput(ios);
    writer.write(null, new IIOImage(imageToCompress, null, null), writeParams);
    writer.dispose();
    ios.close();

    return os.toByteArray();
  }

  private NitfRenderer getNitfRenderer() {
    return new NitfRenderer();
  }
}
