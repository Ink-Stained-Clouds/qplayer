import QtQuick
import md3.Core
import "."

// Outlined playlist card shared by the home and library grids. Ripple is the
// only pointer handler: a normal tap opens the playlist, while desktop right-
// click and a stationary mobile long-press open the context menu at the press
// position. Keeping one handler also prevents the release after a long-press
// from leaking through and opening the playlist behind the menu.
Item {
    id: card

    property var playlistId: 0
    property string name: ""
    property int count: 0
    property string coverUrl: ""
    property string coverThumbPath: ""
    property real tile: 160
    property bool _menuArmed: false
    // A name that wouldn't fit one line at the normal size shrinks and wraps to
    // two instead of eliding away -- the "..." made a lot of these unreadable at
    // a glance on phone-width grids. Measured against nameProbe's real (unwrapped)
    // painted width rather than guessed from character count -- a char-count
    // heuristic under/overshoots badly between CJK and Latin glyph widths.
    property bool longName: nameProbe.contentWidth > card.width - 24
    signal clicked()

    implicitWidth: tile
    implicitHeight: tile + 72

    Rectangle {
        id: container
        x: 0
        y: 0
        width: card.width
        height: card.height
        radius: 18
        color: Theme.color.surfaceContainerLow
        border.width: cardRipple.containsMouse ? 1.5 : 1
        border.color: cardRipple.containsMouse
                      ? Theme.color.outline
                      : Theme.color.outlineVariant

        // A quiet state layer makes the whole tile read as interactive before the
        // press ripple starts, without washing out the cover artwork.
        Rectangle {
            anchors.fill: parent
            radius: parent.radius
            color: Theme.color.onSurfaceColor
            opacity: cardRipple.containsMouse ? 0.04 : 0
            Behavior on opacity {
                NumberAnimation { duration: 140; easing.type: Easing.OutCubic }
            }
        }
    }

    CoverImage {
        id: cover
        x: 8
        y: 8
        width: card.width - 16
        height: card.width - 16
        radius: 12
        icon: "queue_music"
        iconSize: 44
        fadeIn: true
        source: card.coverThumbPath || card.coverUrl
    }

    // Invisible measuring probe: its contentWidth is the name's real, unwrapped
    // painted width at the normal font size -- what decides whether nameLabel
    // below needs the long-name (smaller font, two lines) treatment.
    Text {
        id: nameProbe
        visible: false
        text: card.name
        fontSize: 14
        font.weight: Font.Medium
    }

    // Reserved height covers two lines at all times -- even for a short name --
    // so every card in a row keeps the count label at the same y and the grid
    // never reflows around a long neighbour.
    Text {
        id: nameLabel
        x: 12
        y: card.width - 2
        width: card.width - 24
        height: 36
        verticalAlignment: Text.AlignTop
        text: card.name
        color: Theme.color.onSurfaceColor
        fontSize: card.longName ? 12 : 14
        font.weight: Font.Medium
        wrapMode: card.longName ? Text.WordWrap : Text.NoWrap
        maximumLineCount: card.longName ? 2 : 1
        elide: Text.ElideRight
    }

    // Count is kept in a fixed row (including the zero case) so cards do not
    // reflow when an asynchronously refreshed playlist gains its first song.
    Text {
        x: 12
        y: card.width + 35
        width: card.width - 24
        height: 20
        verticalAlignment: Text.AlignVCenter
        text: card.count > 0 ? (card.count + " 首歌曲") : "暂无歌曲"
        color: Theme.color.onSurfaceVariantColor
        fontSize: 12
        elide: Text.ElideRight
    }

    Ripple {
        id: cardRipple
        x: 0
        y: 0
        width: card.width
        height: card.height
        clipRadius: 18
        rippleColor: Theme.color.onSurfaceColor
        longPressEnabled: true
        onClicked: {
            if (card._menuArmed) {
                card._menuArmed = false
                return
            }
            card.clicked()
        }
        onLongPressed: {
            card._menuArmed = true
            cardMenu.rebuild()
            cardMenu.open(cardRipple, cardRipple.pressX, cardRipple.pressY)
        }
    }

    PlaylistContextMenu {
        id: cardMenu
        playlistId: card.playlistId
        onOpenRequested: card.clicked()
        onClosed: card._menuArmed = false
    }
}
