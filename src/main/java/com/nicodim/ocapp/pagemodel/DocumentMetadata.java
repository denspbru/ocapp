package com.nicodim.ocapp.pagemodel;
import java.net.URI;
public record DocumentMetadata(URI source, String title, String language) {
    public DocumentMetadata { title = title == null ? "" : title; language = language == null ? "" : language; }
}
