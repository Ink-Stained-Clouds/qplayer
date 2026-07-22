package dev.t1m3.qplayer.bridge;

/**
 * One row in the unified search-results list (SearchPage.qml): a flattened,
 * uniformly-shaped view over whichever of {@link PlayerController#searchResults}
 * (netease), {@link PlayerController#localSearchResults} or
 * {@link PlayerController#customSearchResults} it was built from. {@code kind}
 * + {@code index} let {@link PlayerController#playSearchRow(int)} route a click
 * back to the right source-specific play method without re-deriving identity.
 */
public final class SearchRow {
    /** "netease" | "local" | "custom". */
    public String kind;
    /** Index into the source list named by {@link #kind}. */
    public int index;
    public String name;
    public String artist;
    public String coverThumbPath;
    /** Display label for {@link #kind} ("网易云"/"本地"/"自定义源"), shown as a
     *  small per-row tag in SearchPage.qml's unified list — precomputed here so
     *  QML doesn't need its own kind-to-label mapping. */
    public String kindLabel;
}
