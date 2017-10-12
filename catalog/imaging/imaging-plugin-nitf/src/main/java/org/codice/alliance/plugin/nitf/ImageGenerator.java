package org.codice.alliance.plugin.nitf;

import ddf.catalog.content.data.ContentItem;
import java.awt.Dimension;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;

public interface ImageGenerator {

  void setImageSource(ContentItem contentItem, Map<String, Serializable> properties);

  ImageSource createImage(Dimension outputDimensions, OutputFormat format) throws IOException;

}
