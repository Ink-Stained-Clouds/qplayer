import QtQuick
import md3.Core
import "."

// Windowed song list. Only a fixed pool of SongRows exists; as you scroll, each
// delegate maps to the item whose index ≡ its slot (mod pool size), so a one-row
// scroll recycles exactly ONE delegate (not all of them) — avoids the per-row
// stutter a naive `first+index` window caused. `list` is the full List Property.
Flickable {
    id: view

    property var list
    property bool isLocal: false
    property int rowH: 64
    property int activatedIndex: -1
    signal activated()

    property int count: list ? list.length : 0
    // Fixed pool size (independent of scroll position so the Repeater never
    // rebuilds mid-scroll); +4 buffer rows above/below the viewport.
    property int pool: Math.min(count, Math.ceil(height / rowH) + 4)
    property int first: Math.max(0, Math.floor(contentY / rowH) - 2)

    clip: true
    contentWidth: width
    contentHeight: count * rowH

    Item {
        width: view.width
        height: view.contentHeight

        Repeater {
            model: view.pool
            SongRow {
                // Item index this slot currently shows (only one slot's value
                // changes when `first` steps by one).
                property int abs: view.pool > 0
                    ? view.first + (((index - view.first) % view.pool) + view.pool) % view.pool
                    : -1
                width: view.width
                y: abs * view.rowH
                visible: abs >= 0 && abs < view.count
                rowTitle: { var it = (abs >= 0 && abs < view.count) ? view.list[abs] : null;
                            return it ? (view.isLocal ? it.title : it.name) : "" }
                rowArtist: { var it = (abs >= 0 && abs < view.count) ? view.list[abs] : null;
                             return it ? it.artist : "" }
                highlighted: view.isLocal && abs === player.index
                onActivated: { view.activatedIndex = abs; view.activated() }
            }
        }
    }
}
