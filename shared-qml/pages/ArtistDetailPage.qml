import QtQuick
import QtQuick.Layouts
import md3.Core
import "."
import "../components"

// Drill-in artist view: header with back + name, then profile (avatar + bio),
// an album grid, and hot songs -- all one Flickable with absolute positioning,
// the same layout primitive HomePage.qml uses to mix a grid with a song list
// (a real Repeater-in-Layout can't host two differently-shaped sections).
Rectangle {
    id: page
    signal back()
    color: Theme.color.surface

    property bool loadingWatch: player.artistLoading
    onLoadingWatchChanged: if (player.artistLoading) scroller.contentY = 0

    // Swallow taps on empty areas so they don't reach the page beneath.
    MouseArea { anchors.fill: parent }

    property real pad: 16
    property real gap: 12
    property real avatarSize: 72
    property real profileTop: 12
    // Room reserved under the avatar for up to a 3-line bio.
    property real descReserve: 56
    property real profileH: avatarSize + descReserve
    property int albumCount: player.artistAlbums ? player.artistAlbums.length : 0
    property int songCount: player.artistSongs ? player.artistSongs.length : 0
    property real minAlbumTile: 130
    property int albumCols: Math.max(2, Math.floor((width - 2 * pad + gap) / (minAlbumTile + gap)))
    property real albumTile: (width - 2 * pad - (albumCols - 1) * gap) / albumCols
    property real albumCardH: albumTile + 56
    property real albumsHdrY: profileTop + profileH + 8
    property real albumsTop: albumsHdrY + (albumCount > 0 ? 40 : 0)
    property real albumsGridH: albumCount > 0 ? Math.ceil(albumCount / albumCols) * (albumCardH + gap) : 0
    property real songsHdrY: albumsTop + albumsGridH + (albumCount > 0 ? 4 : 0)
    property real songsTop: songsHdrY + (songCount > 0 ? 40 : 0)
    property real rowH: 64

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // Custom header: qml4j can't set a sub-property of TopAppBar's
        // navigationIcon alias (navigationIcon.icon) via grouped binding.
        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 64
            Layout.leftMargin: 4
            Layout.rightMargin: 16
            spacing: 4
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"; icon: "arrow_back"
                onClicked: page.back()
            }
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: player.artistName
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
                verticalAlignment: Text.AlignVCenter
                elide: Text.ElideRight
            }
        }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            Flickable {
                id: scroller
                anchors.fill: parent
                visible: !player.artistLoading
                clip: true
                contentWidth: width
                contentHeight: page.songsTop + page.songCount * page.rowH + 24

                Item {
                    width: scroller.width
                    height: scroller.contentHeight
                    cachedLayout: true

                    CoverImage {
                        id: avatar
                        x: page.pad; y: page.profileTop
                        width: page.avatarSize; height: page.avatarSize
                        radius: page.avatarSize / 2
                        icon: "person"
                        iconSize: 32
                        fadeIn: true
                        source: player.artistCoverPath
                    }

                    Text {
                        anchors.left: avatar.right
                        anchors.leftMargin: 14
                        anchors.right: parent.right
                        anchors.rightMargin: page.pad
                        anchors.top: avatar.top
                        text: (page.albumCount > 0 || page.songCount > 0)
                              ? (page.albumCount + " 张专辑 · " + page.songCount + " 首热门歌曲")
                              : ""
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 13
                        elide: Text.ElideRight
                    }

                    Text {
                        anchors.left: parent.left
                        anchors.leftMargin: page.pad
                        anchors.right: parent.right
                        anchors.rightMargin: page.pad
                        anchors.top: avatar.bottom
                        anchors.topMargin: 10
                        visible: player.artistBriefDesc !== ""
                        text: player.artistBriefDesc
                        wrapMode: Text.WordWrap
                        maximumLineCount: 3
                        elide: Text.ElideRight
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 12
                    }

                    Text {
                        visible: page.albumCount > 0
                        x: page.pad; y: page.albumsHdrY; height: 40
                        verticalAlignment: Text.AlignVCenter
                        text: "专辑"
                        color: Theme.color.primary
                        fontSize: 16
                    }

                    Repeater {
                        model: player.artistAlbums
                        AlbumCard {
                            albumId: modelData.id
                            tile: page.albumTile
                            x: page.pad + (index % page.albumCols) * (page.albumTile + page.gap)
                            y: page.albumsTop + Math.floor(index / page.albumCols) * (page.albumCardH + page.gap)
                            name: modelData.name
                            count: modelData.trackCount
                            coverUrl: modelData.coverUrl
                            coverThumbPath: modelData.coverThumbPath || ""
                            onClicked: player.openAlbum(modelData.id)
                        }
                    }

                    Text {
                        visible: page.songCount > 0
                        x: page.pad; y: page.songsHdrY; height: 40
                        verticalAlignment: Text.AlignVCenter
                        text: "热门歌曲"
                        color: Theme.color.primary
                        fontSize: 16
                    }

                    Repeater {
                        model: player.artistSongs
                        SongRow {
                            width: scroller.width
                            y: page.songsTop + index * page.rowH
                            rowTitle: modelData.name
                            rowArtist: modelData.artist
                            rowArtistId: modelData.artistId || 0
                            coverThumbPath: modelData.coverThumbPath || ""
                            onActivated: player.playArtistSong(index)
                        }
                    }
                }
            }

            LoadingIndicator {
                anchors.centerIn: parent
                visible: player.artistLoading
                running: player.artistLoading
                withContainer: true
                size: 56
            }
        }
    }
}
