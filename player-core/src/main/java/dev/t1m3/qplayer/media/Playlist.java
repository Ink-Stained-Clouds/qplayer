package dev.t1m3.qplayer.media;

import java.util.ArrayList;
import java.util.List;

/** Source-neutral playlist detail DTO. */
public final class Playlist {
    public String id = "";
    public String name = "";
    public String description = "";
    public String artworkUrl = "";
    public String coverUrl = "";
    public String coverThumbPath = "";
    /** Display name of the source that owns this playlist. Set by the host when a
     *  list mixes several sources (我的); empty in single-source contexts. */
    public String sourceName = "";
    public MediaRef owner;
    public long trackCount;
    public long playCount;
    public boolean subscribed;
    public boolean owned;
    public boolean mutable;
    public boolean deletable;
    public List<Song> songs = new ArrayList<>();
}
