import QtQuick
import QtQuick.Layouts
import md3.Core

// A playlist tile for the home / library grids: placeholder cover art, name,
// and track count. Network cover loading is a later pass — for now a glyph.
Item {
    id: card

    property string name: ""
    property int count: 0
    signal clicked()

    width: 156
    implicitHeight: 200

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 6
        spacing: 6

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: width
            radius: 12
            color: cardMa.containsMouse ? Theme.color.surfaceContainerHighest
                                        : Theme.color.surfaceContainer
            Text {
                anchors.centerIn: parent
                text: "queue_music"
                font.family: Theme.iconFont.name
                font.pixelSize: 44
                color: Theme.color.onSurfaceVariantColor
            }
        }

        Text {
            Layout.fillWidth: true
            text: card.name
            color: Theme.color.onSurfaceColor
            fontSize: 14
            elide: Text.ElideRight
        }
        Text {
            Layout.fillWidth: true
            visible: card.count > 0
            text: card.count + " 首"
            color: Theme.color.onSurfaceVariantColor
            fontSize: 12
        }
    }

    MouseArea {
        id: cardMa
        anchors.fill: parent
        hoverEnabled: true
        onClicked: card.clicked()
    }
}
