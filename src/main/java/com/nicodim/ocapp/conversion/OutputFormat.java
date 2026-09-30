package com.nicodim.ocapp.conversion;

public enum OutputFormat {
    MHTML("application/x-mimearchive", "mhtml"),
    PDF("application/pdf", "pdf"),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
    private final String mediaType;
    private final String extension;
    OutputFormat(String mediaType, String extension) { this.mediaType = mediaType; this.extension = extension; }
    public String mediaType() { return mediaType; }
    public String extension() { return extension; }
}
