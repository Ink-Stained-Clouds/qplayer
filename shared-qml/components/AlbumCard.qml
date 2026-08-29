import QtQuick
import md3.Core
import "."

// Compact album tile for the artist page's album grid. Trimmed-down sibling of
// PlaylistCard (no context menu -- albums don't have one).
Item {
    id: card

    property var albumId: 0
    property string name: ""
    property int count: 0
    property string coverUrl: ""
    property string coverThumbPath: ""
    property real tile: 130
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
        icon: "album"
        iconSize: 32
        fadeIn: true
        source: card.coverThumbPath || card.coverUrl
    }

    MarqueeText {
        x: 8
        y: card.width - 2
        width: card.width - 16
        height: 32
        text: card.name
        textColor: Theme.color.onSurfaceColor
        fontSize: 12
        fontWeight: Font.Medium
    }

    Text {
        x: 8
        y: card.width + 30
        width: card.width - 16
        height: 18
        verticalAlignment: Text.AlignVCenter
        text: card.count > 0 ? (card.count + " 首") : ""
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
