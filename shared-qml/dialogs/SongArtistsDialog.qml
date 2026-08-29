import QtQuick
import QtQuick.Layouts
import md3.Core
import "../components"

// SongContextMenu's "查看歌手" -- a song can credit more than one artist, so
// this shows every one of them (same card look as SearchPage's artist-mode
// grid) and lets the user pick whose page to open, instead of always jumping
// to the first-listed credit. State-driven off PlayerController (same idiom
// as ListenTogetherDialog/LoginDialog in Main.qml) rather than an imperative
// open(list) call, since SongContextMenu is instantiated deep inside many
// different lists and has no direct reference to a single dialog instance.
Rectangle {
    id: dialog
    anchors.fill: parent
    opacity: player.songArtistPickerOpen ? 1 : 0
    visible: opacity > 0.01
    color: "#99000000"
    Behavior on opacity { NumberAnimation { duration: 150 } }

    MouseArea { anchors.fill: parent; onClicked: player.closeSongArtistPicker() }

    property var artists: player.songArtistPickerList || []
    property int count: artists.length
    property real pad: 16
    property real gap: 10
    // Panel width scales with the app window itself, not a fixed constant:
    // a narrow window can't fit more than one ~150px column, so cols
    // resolves to 1 and every artist just stacks vertically in the
    // Flickable below (scrollable); a wide/maximized window both fits more
    // columns AND grows panelWidth itself, so the cards get visibly bigger
    // there too, not just more numerous. Tiles then stretch to exactly fill
    // panelWidth / cols -- deliberately, so a 1-2 artist row still reads as
    // a big, intentional card rather than a small one floating in empty
    // space. PlayerController#fetchSongArtistAvatars requests a 512px
    // avatar source specifically to stay sharp at the largest size this can
    // stretch to.
    property real minTile: 150
    property real panelWidth: Math.max(240, Math.min(parent.width - 48, parent.width * 0.42, 560))
    property int maxCols: Math.max(1, Math.floor((panelWidth - 2 * pad + gap) / (minTile + gap)))
    property int cols: Math.max(1, Math.min(maxCols, count))
    property real tile: (panelWidth - 2 * pad - (cols - 1) * gap) / Math.max(1, cols)
    property real gridCardH: tile + 56
    property real gridH: Math.ceil(count / Math.max(1, cols)) * (gridCardH + gap)

    Rectangle {
        id: panel
        anchors.centerIn: parent
        width: dialog.panelWidth
        height: Math.min(dialog.height - 96, 64 + dialog.gridH + dialog.pad)
        radius: 24
        color: Theme.color.surfaceContainerHigh
        scale: dialog.opacity > 0.5 ? 1 : 0.94
        Behavior on scale { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }

        // Swallow taps on the panel so they don't fall through to the scrim.
        MouseArea { anchors.fill: parent }

        Text {
            id: header
            anchors.top: parent.top
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.margins: dialog.pad
            height: 32
            verticalAlignment: Text.AlignVCenter
            text: "查看歌手"
            color: Theme.color.onSurfaceColor
            font.family: Theme.typography.titleMedium.family
            font.pixelSize: Theme.typography.titleMedium.size
        }

        Flickable {
            id: grid
            anchors.top: header.bottom
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.margins: dialog.pad
            clip: true
            contentWidth: width
            contentHeight: dialog.gridH

            Item {
                width: grid.width
                height: grid.contentHeight

                Repeater {
                    model: dialog.artists
                    ArtistCard {
                        artistId: modelData.id
                        name: modelData.name
                        coverUrl: modelData.coverUrl || ""
                        coverThumbPath: modelData.coverThumbPath || ""
                        tile: dialog.tile
                        x: (index % dialog.cols) * (dialog.tile + dialog.gap)
                        y: Math.floor(index / dialog.cols) * (dialog.gridCardH + dialog.gap)
                        onClicked: {
                            player.closeSongArtistPicker()
                            player.openArtist(modelData.id)
                        }
                    }
                }
            }
        }
    }
}
