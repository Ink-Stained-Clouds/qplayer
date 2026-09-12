import QtQuick
import md3.Core

// One tappable row inside SourceAccountDialog: leading icon, label, hover
// surface. Mirrors ArtistRow's interaction shape so the two read the same.
Rectangle {
    id: action

    property string icon: ""
    property string text: ""
    /** Destructive actions (sign out) take the error colour. */
    property bool danger: false
    signal activated()

    color: "transparent"
    readonly property color tint: danger ? Theme.color.error : Theme.color.onSurfaceColor

    Rectangle {
        anchors.fill: parent
        anchors.leftMargin: 4
        anchors.rightMargin: 4
        anchors.topMargin: 3
        anchors.bottomMargin: 3
        radius: 12
        color: Theme.color.surfaceContainerHighest
        opacity: ripple.containsMouse ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
    }

    Text {
        id: glyph
        anchors.left: parent.left
        anchors.leftMargin: 18
        anchors.verticalCenter: parent.verticalCenter
        text: action.icon
        font.family: Theme.iconFont.name
        font.pixelSize: 22
        color: action.tint
    }

    Text {
        anchors.left: glyph.right
        anchors.leftMargin: 18
        anchors.right: parent.right
        anchors.rightMargin: 16
        anchors.verticalCenter: parent.verticalCenter
        elide: Text.ElideRight
        text: action.text
        color: action.tint
        font.family: Theme.typography.bodyLarge.family
        font.pixelSize: Theme.typography.bodyLarge.size
    }

    MouseArea {
        id: ripple
        anchors.fill: parent
        hoverEnabled: true
        onClicked: action.activated()
    }
}
