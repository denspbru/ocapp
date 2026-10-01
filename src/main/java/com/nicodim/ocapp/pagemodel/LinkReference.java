package com.nicodim.ocapp.pagemodel;
import java.net.URI;
public record LinkReference(String text, URI target) { public LinkReference { text = text == null ? "" : text; } }
