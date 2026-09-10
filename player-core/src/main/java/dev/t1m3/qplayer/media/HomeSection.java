package dev.t1m3.qplayer.media;

import java.util.ArrayList;
import java.util.List;

/**
 * One titled group of playlists on the home page, e.g. NetEase's radar lists.
 *
 * <p>Sections are drawn above the untitled recommendation grid, in the order the
 * provider returned them, so a source can keep the lists its users reach for
 * every day out of the long tail of generic recommendations.
 */
public final class HomeSection {
    public String title = "";
    public List<Playlist> playlists = new ArrayList<>();
    /** Index of this section's first card in the published flat card list. */
    public int start;
    public int count;
}
