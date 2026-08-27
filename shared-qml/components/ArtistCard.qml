import QtQuick
import md3.Core
import "."

// Compact artist tile for SearchPage's artist-mode grid. Same card shape as
// AlbumCard/PlaylistCard (playlist-cover style), just a "person" placeholder
// and a song-count line instead of a track count.
Item {
    id: card

    property var artistId: 0
    property string name: ""
    property int count: 0
    property string coverUrl: ""
    property string coverThumbPath: ""
    property real tile: 130
    property bool longName: nameProbe.contentWidth > card.width - 16
    signal clicked()

    implicitWidth: tile
    implicitHeight: tile + 56

    Rectangle {
        x: 0; y: 0
        width: card.width; height: card.height
        radius: 14
        color: Theme.color.surfaceContainerLow
        border.width: cardRipple.containsMouse ? 1.5 : 1
        border.color: cardRipple.containsMouse
                      ? Theme.color.outline
                      : Theme.color.outlineVariant
    }

    CoverImage {
        id: cover
        x: 6; y: 6
        width: card.width - 12
        height: card.width - 12
        radius: 10
        icon: "person"
        iconSize: 32
        fadeIn: true
        source: card.coverThumbPath || card.coverUrl
    }

    Text {
        id: nameProbe
        visible: false
        text: card.name
        fontSize: 12
        font.weight: Font.Medium
    }

    Text {
        x: 8
        y: card.width - 2
        width: card.width - 16
        height: 32
        verticalAlignment: Text.AlignTop
        text: card.name
        color: Theme.color.onSurfaceColor
        fontSize: card.longName ? 11 : 12
        font.weight: Font.Medium
        wrapMode: card.longName ? Text.WordWrap : Text.NoWrap
        maximumLineCount: card.longName ? 2 : 1
        elide: Text.ElideRight
    }

    Text {
        x: 8
        y: card.width + 30
        width: card.width - 16
        height: 18
        verticalAlignment: Text.AlignVCenter
        text: card.count > 0 ? (card.count + " 首歌曲") : ""
        color: Theme.color.onSurfaceVariantColor
        fontSize: 11
        elide: Text.ElideRight
    }

    Ripple {
        id: cardRipple
        x: 0; y: 0
        width: card.width; height: card.height
        clipRadius: 14
        rippleColor: Theme.color.onSurfaceColor
        onClicked: card.clicked()
    }
}
