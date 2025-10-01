package eu.nurkert.neverUp2Late.fetcher.exception;

import java.util.List;
import java.util.Objects;

/**
 * Exception indicating that a release contains multiple matching assets and a
 * manual selection is required to proceed.
 */
public class AssetSelectionRequiredException extends Exception {

    private final String releaseTag;
    private final List<ReleaseAsset> assets;
    private final AssetType assetType;

    public AssetSelectionRequiredException(String releaseTag,
                                            List<ReleaseAsset> assets,
                                            AssetType assetType) {
        super(buildMessage(releaseTag, assetType, assets));
        this.releaseTag = releaseTag;
        this.assets = List.copyOf(assets);
        this.assetType = assetType;
    }

    public String getReleaseTag() {
        return releaseTag;
    }

    public List<ReleaseAsset> getAssets() {
        return assets;
    }

    public AssetType getAssetType() {
        return assetType;
    }

    private static String buildMessage(String releaseTag, AssetType assetType, List<ReleaseAsset> assets) {
        String typeLabel = assetType == null ? "asset" : assetType.displayName();
        return "Multiple " + typeLabel + " candidates for release " + releaseTag + ": " + assets.size();
    }

    public enum AssetType {
        JAR("JAR"),
        ARCHIVE("Archive"),
        UNKNOWN("Asset");

        private final String displayName;

        AssetType(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public record ReleaseAsset(String name, String downloadUrl, boolean archive) {
        public ReleaseAsset {
            Objects.requireNonNull(downloadUrl, "downloadUrl");
        }
    }
}
