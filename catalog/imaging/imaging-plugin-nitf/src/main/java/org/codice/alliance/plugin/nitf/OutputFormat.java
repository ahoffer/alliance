package org.codice.alliance.plugin.nitf;

public enum OutputFormat {
  JPEG("jpg", "image/jpeg"),
  JPEG_2000("jp2", "image/jp2");

  private String extension;
  private String mimeType;

  OutputFormat(String extension, String mimeType){
    this.extension = extension;
    this.mimeType = mimeType;
  }

  public String getExtension() {
    return this.extension;
  }

  public String getMimeType() {
    return this.mimeType;
  }
}
