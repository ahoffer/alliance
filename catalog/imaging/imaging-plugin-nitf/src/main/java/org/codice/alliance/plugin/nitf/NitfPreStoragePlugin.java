/**
 * Copyright (c) Codice Foundation
 * <p/>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p/>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.alliance.plugin.nitf;

import com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi;
import ddf.catalog.content.data.ContentItem;
import ddf.catalog.content.data.impl.ContentItemImpl;
import ddf.catalog.content.operation.CreateStorageRequest;
import ddf.catalog.content.operation.UpdateStorageRequest;
import ddf.catalog.content.plugin.PreCreateStoragePlugin;
import ddf.catalog.content.plugin.PreUpdateStoragePlugin;
import ddf.catalog.data.Attribute;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.impl.AttributeImpl;
import ddf.catalog.data.types.Core;
import ddf.catalog.data.types.Media;
import ddf.catalog.plugin.PluginExecutionException;
import java.awt.Dimension;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import javax.imageio.spi.IIORegistry;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This pre-storage plugin creates and stores the NITF thumbnail and NITF overview images. The
 * thumbnail is stored with the Metacard while the overview is stored in the content store.
 */
public class NitfPreStoragePlugin implements PreCreateStoragePlugin, PreUpdateStoragePlugin {

  private static final String IMAGE_NITF = "image/nitf";

  static final MimeType NITF_MIME_TYPE;

  static {
    try {
      NITF_MIME_TYPE = new MimeType(IMAGE_NITF);
    } catch (MimeTypeParseException e) {
      throw new ExceptionInInitializerError(String.format(
          "Unable to create MimeType from '%s': %s", IMAGE_NITF, e.getMessage()));
    }
  }

  private static final int THUMBNAIL_WIDTH = 200;

  private static final int THUMBNAIL_HEIGHT = 200;

  private static final Logger LOGGER = LoggerFactory.getLogger(NitfPreStoragePlugin.class);

  private static final String OVERVIEW = "overview";

  private static final String ORIGINAL = "original";

  private static final String DERIVED_IMAGE_FILENAME_PATTERN = "%s-%s.%s";

  // non-word characters equivalent to [^a-zA-Z0-9_]
  private static final String INVALID_FILENAME_CHARACTER_REGEX = "[\\W]";

  private static final double DEFAULT_MAX_SIDE_LENGTH = 1024.0;

  private static final int DEFAULT_MAX_NITF_SIZE = 120;

  private int maxNitfSizeMB = DEFAULT_MAX_NITF_SIZE;

  private boolean createOverview = true;

  private boolean storeOriginalImage = true;

  private OutputFormat thumbnailFormat = OutputFormat.JPEG;

  private OutputFormat overviewFormat = OutputFormat.JPEG;

  private OutputFormat originalFormat = OutputFormat.JPEG_2000;

  static {
    IIORegistry.getDefaultInstance()
        .registerServiceProvider(new J2KImageReaderSpi());
  }

  private double maxSideLength = DEFAULT_MAX_SIDE_LENGTH;

  @Override
  public CreateStorageRequest process(CreateStorageRequest createStorageRequest)
      throws PluginExecutionException {
    if (createStorageRequest == null) {
      throw new PluginExecutionException(
          "process(): argument 'createStorageRequest' may not be null.");
    }

    process(createStorageRequest.getContentItems(), createStorageRequest.getProperties());
    return createStorageRequest;
  }

  @Override
  public UpdateStorageRequest process(UpdateStorageRequest updateStorageRequest)
      throws PluginExecutionException {
    if (updateStorageRequest == null) {
      throw new PluginExecutionException(
          "process(): argument 'updateStorageRequest' may not be null.");
    }

    process(updateStorageRequest.getContentItems(), updateStorageRequest.getProperties());
    return updateStorageRequest;
  }

  private boolean isNitfMimeType(String rawMimeType) {
    try {
      return NITF_MIME_TYPE.match(rawMimeType);
    } catch (MimeTypeParseException e) {
      LOGGER.debug("unable to compare mime types: {} vs {}",
          NITF_MIME_TYPE,
          rawMimeType);
    }

    return false;
  }

  private void process(List<ContentItem> contentItems, Map<String, Serializable> properties) {
    List<ContentItem> newContentItems = new ArrayList<>();
    contentItems.forEach(contentItem -> process(contentItem, newContentItems, properties));
    contentItems.addAll(newContentItems);
  }

  private void process(ContentItem contentItem, List<ContentItem> contentItems,
      Map<String, Serializable> properties) {

    if (!isNitfMimeType(contentItem.getMimeTypeRawData())) {
      LOGGER.debug("skipping content item: filename={} mimeType={}",
          contentItem.getFilename(),
          contentItem.getMimeTypeRawData());
      return;
    }

    LOGGER.info("Pre-storage processing starting for input: " + contentItem.getId());

    Metacard metacard = contentItem.getMetacard();

    Dimension imageDimensions = new Dimension();
    imageDimensions.width = ((Number) metacard.getAttribute(Media.WIDTH).getValue()).intValue();
    imageDimensions.height = ((Number) metacard.getAttribute(Media.HEIGHT).getValue()).intValue();

    ImageGenerator externalGenerator = new GdalImageGenerator();
    ImageGenerator defaultGenerator = new DefaultImageGenerator(maxNitfSizeMB);
    try {
      externalGenerator.setImageSource(contentItem, properties);
      defaultGenerator.setImageSource(contentItem, properties);

      LOGGER.info("Generating thumbnail for input: " + contentItem.getId());
      int maxThumbnailLength = Math.max(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
      Dimension thumbnailDim = calculateImageDimension(imageDimensions.width,
          imageDimensions.height, maxThumbnailLength);

      ImageSource thumbnailSource = null;
      try {
        thumbnailSource = externalGenerator.createImage(thumbnailDim, thumbnailFormat);
      } catch (IOException | RuntimeException ioe) {
        LOGGER.debug(
            "Failed to generate thumbnail externally, falling back to in-memory processing...");
        thumbnailSource = defaultGenerator.createImage(thumbnailDim, thumbnailFormat);
      }

      if (thumbnailSource != null) {
        metacard
            .setAttribute(new AttributeImpl(Core.THUMBNAIL, thumbnailSource.getImageData().read()));
      } else {
        LOGGER.warn("Thumbnail image generation failed for input: " + contentItem.getId());
      }

      if (createOverview) {
        Dimension dim = calculateImageDimension(imageDimensions.width,
            imageDimensions.height, (int) maxSideLength);
        LOGGER.debug("Generating overview for input: " + contentItem.getId());

        ImageSource overviewSource = null;
        try {
          overviewSource = externalGenerator.createImage(dim, overviewFormat);
        } catch (IOException ioe) {
          LOGGER.debug(
              "Failed to generate overview externally, falling back to in-memory processing...");
          overviewSource = defaultGenerator.createImage(dim, overviewFormat);
        }

        if (overviewSource != null) {
          ContentItem overviewItem = new ContentItemImpl(metacard.getId(),
              OVERVIEW,
              overviewSource.getImageData(),
              overviewFormat.getMimeType(),
              buildDerivedImageTitle(metacard.getTitle(), OVERVIEW, overviewFormat.getExtension()),
              overviewSource.getSize(),
              metacard);
          addDerivedResourceAttribute(metacard, overviewItem);
          contentItems.add(overviewItem);
        } else {
          LOGGER.warn("Overview image generation failed for input: " + contentItem.getId());
        }
      }

      if (storeOriginalImage) {
        LOGGER.debug("Generating original for input: " + contentItem.getId());

        ImageSource originalSource = null;
        try {
          originalSource = externalGenerator
              .createImage(imageDimensions, originalFormat);
        } catch (IOException ioe) {
          LOGGER.debug(
              "Failed to generate original externally, falling back to in-memory processing...");
          originalSource = defaultGenerator
              .createImage(imageDimensions, originalFormat);
        }

        if (originalSource != null) {
          ContentItem originalItem = new ContentItemImpl(metacard.getId(),
              ORIGINAL,
              originalSource.getImageData(),
              originalFormat.getMimeType(),
              buildDerivedImageTitle(metacard.getTitle(), ORIGINAL, originalFormat.getExtension()),
              originalSource.getSize(),
              metacard);
          addDerivedResourceAttribute(metacard, originalItem);
          contentItems.add(originalItem);
        } else {
          LOGGER.warn("Original image generation failed for input: " + contentItem.getId());
        }
      }
    } catch (IOException | RuntimeException e) {
      LOGGER.debug("Failed to generate images for content: " + contentItem.getId(), e);
    }

    LOGGER.info("Pre-storage processing complete for input: " + contentItem.getId());
  }

  private String buildDerivedImageTitle(String title, String qualifier, String extension) {
    String rootFileName = FilenameUtils.getBaseName(title);

    // title must contain some alphanumeric, human readable characters, or use default filename
    if (StringUtils.isNotBlank(rootFileName) && StringUtils.isNotBlank(rootFileName.replaceAll(
        "[^A-Za-z0-9]",
        ""))) {
      String strippedFilename = rootFileName.replaceAll(INVALID_FILENAME_CHARACTER_REGEX, "");
      return String.format(DERIVED_IMAGE_FILENAME_PATTERN,
          qualifier,
          strippedFilename,
          extension)
          .toLowerCase();
    }

    return String.format("%s.%s", qualifier, extension)
        .toLowerCase();
  }

  private void addDerivedResourceAttribute(Metacard metacard, ContentItem contentItem) {
    Attribute attribute = metacard.getAttribute(Core.DERIVED_RESOURCE_URI);
    if (attribute == null) {
      attribute = new AttributeImpl(Core.DERIVED_RESOURCE_URI, contentItem.getUri());
    } else {
      AttributeImpl newAttribute = new AttributeImpl(attribute);
      newAttribute.addValue(contentItem.getUri());
      attribute = newAttribute;
    }

    metacard.setAttribute(attribute);
  }

  private Dimension calculateImageDimension(int imageWidth, int imageHeight, int maxSideLength) {
    Dimension dim = new Dimension();

    if (imageWidth >= imageHeight) {
      dim.width = Math.min(imageWidth, maxSideLength);
      dim.height = (int) Math.round(imageHeight * ((double) dim.width / (double) imageWidth));
    } else {
      dim.height = Math.min(imageHeight, maxSideLength);
      dim.width = (int) Math.round(
          imageWidth * ((double) dim.height / (double) imageHeight));
    }

    return dim;
  }

  public void setMaxSideLength(int maxSideLength) {
    if (maxSideLength > 0) {
      LOGGER.trace("Setting derived image maxSideLength to {}", maxSideLength);
      this.maxSideLength = maxSideLength;
    } else {
      LOGGER.debug(
          "Invalid `maxSideLength` value [{}], must be greater than zero. Default value [{}] will be used instead.",
          maxSideLength,
          DEFAULT_MAX_SIDE_LENGTH);
      this.maxSideLength = DEFAULT_MAX_SIDE_LENGTH;
    }
  }

  public void setMaxNitfSizeMB(int maxNitfSizeMB) {
    this.maxNitfSizeMB = maxNitfSizeMB;
  }

  public void setCreateOverview(boolean createOverview) {
    this.createOverview = createOverview;
  }

  public void setStoreOriginalImage(boolean storeOriginalImage) {
    this.storeOriginalImage = storeOriginalImage;
  }
}
