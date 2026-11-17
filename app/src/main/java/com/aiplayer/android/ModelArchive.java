package com.aiplayer.android;

import java.io.File;
import java.io.IOException;

final class ModelArchive {
    private ModelArchive() {}

    static File destination(File root, String entry) throws IOException {
        File destination = new File(root, entry);
        String prefix = root.getCanonicalPath() + File.separator;
        if (!destination.getCanonicalPath().startsWith(prefix)) {
            throw new IOException("Invalid model archive path: " + entry);
        }
        return destination;
    }
}
