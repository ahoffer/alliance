package org.codice.alliance.plugin.nitf;

import com.google.common.io.ByteSource;

public class ImageSource {

  private ByteSource imageData;
  private long size;

  public ImageSource() {}

  public ImageSource(ByteSource imageSource, long size) {
    this.imageData = imageSource;
    this.size = size;
  }

  public ByteSource getImageData() {
    return imageData;
  }

  public void setImageData(ByteSource imageData) {
    this.imageData = imageData;
  }

  public long getSize() {
    return size;
  }

  public void setSize(long size) {
    this.size = size;
  }
}
