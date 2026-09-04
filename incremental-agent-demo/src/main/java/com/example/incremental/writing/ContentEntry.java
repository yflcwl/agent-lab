package com.example.incremental.writing;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.Instant;

public record ContentEntry(
        int sequence,
        String title,
        @JsonAlias("templateReference") String referenceBasis,
        String filename,
        Instant createdAt) {
}

