package com.mineplus.pack.compile;

import java.io.File;

/**
 * One compiled resource pack artifact: a hash-named zip on disk plus the
 * metadata delivery and diagnostics need. Immutable.
 *
 * @param file            the artifact zip ({@code mp-<graphHash12>.zip})
 * @param graphHash       SHA-256 over the ordered asset-identity/content-hash graph;
 *                        same registrations -> same artifact name -> cache hit
 * @param sha1            SHA-1 of the artifact bytes (the client pack protocol's hash)
 * @param packFormat      the {@code pack.mcmeta} pack_format used
 * @param itemRepresentation which item-model strategy produced the item files
 * @param assetCount      assets written into the zip
 * @param byteSize        artifact size in bytes
 * @param compileMillis   wall time the (non-cached) compile took; 0 for cache hits
 */
public record PackArtifact(
        File file,
        String graphHash,
        String sha1,
        int packFormat,
        com.mineplus.pack.PackFormat.ItemRepresentation itemRepresentation,
        int assetCount,
        long byteSize,
        long compileMillis
) {

    /** Short artifact base name ({@code mp-<first 12 graph-hash chars>}). */
    public String artifactName() {
        return "mp-" + graphHash.substring(0, Math.min(12, graphHash.length())) + ".zip";
    }
}
