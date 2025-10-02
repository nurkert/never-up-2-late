package eu.nurkert.neverUp2Late.update;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Post processing hook that can transform the file downloaded by the update pipeline
 * before it is handed to the installation step.
 */
@FunctionalInterface
public interface DownloadProcessor {

    /**
     * Performs in-place processing of the downloaded artifact and returns the path
     * to the resulting file that should be installed.
     *
     * @param context         update context that triggered the download
     * @param downloadedFile  path to the file written by the downloader
     * @return path to the processed artifact that should be installed
     * @throws IOException if processing fails
     */
    Path process(UpdateContext context, Path downloadedFile) throws IOException;
}
