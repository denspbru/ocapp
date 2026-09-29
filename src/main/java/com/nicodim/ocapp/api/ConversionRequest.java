package com.nicodim.ocapp.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConversionRequest(@NotBlank @Size(max = 4096) String url) { }
